package com.kakao.s2graph.core.storage.hbase

import java.util

import com.google.common.cache.Cache
import com.kakao.s2graph.core.GraphExceptions.FetchTimeoutException
import com.kakao.s2graph.core._
import com.kakao.s2graph.core.mysqls.{Label, LabelMeta}
import com.kakao.s2graph.core.storage.Storage
import com.kakao.s2graph.core.types._
import com.kakao.s2graph.core.utils.{Extensions, logger}
import com.stumbleupon.async.Deferred
import com.typesafe.config.Config
import org.hbase.async._

import scala.collection.JavaConversions._
import scala.collection.Seq
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future, duration}
import scala.util.hashing.MurmurHash3


object AsynchbaseStorage {
  val vertexCf = HSerializable.vertexCf
  val edgeCf = HSerializable.edgeCf
  val emptyKVs = new util.ArrayList[KeyValue]()


  def makeClient(config: Config, overrideKv: (String, String)*) = {
    val asyncConfig: org.hbase.async.Config = new org.hbase.async.Config()

    for (entry <- config.entrySet() if entry.getKey.contains("hbase")) {
      asyncConfig.overrideConfig(entry.getKey, entry.getValue.unwrapped().toString)
    }

    for ((k, v) <- overrideKv) {
      asyncConfig.overrideConfig(k, v)
    }

    val client = new HBaseClient(asyncConfig)
    logger.info(s"Asynchbase: ${client.getConfig.dumpConfiguration()}")
    client
  }
}

class AsynchbaseStorage(config: Config, cache: Cache[Integer, Seq[QueryResult]], vertexCache: Cache[Integer, Option[Vertex]])
                       (implicit ec: ExecutionContext) extends Storage {

  import AsynchbaseStorage._

  //  import Extensions.FutureOps

  import Extensions.DeferOps

  val client = AsynchbaseStorage.makeClient(config)
  val queryBuilder = new AsynchbaseQueryBuilder(this)(ec)
  val mutationBuilder = new AsynchbaseMutationBuilder(this)(ec)

  val cacheOpt = Option(cache)
  val vertexCacheOpt = Option(vertexCache)

  private val clientWithFlush = AsynchbaseStorage.makeClient(config, "hbase.rpcs.buffered_flush_interval" -> "0")
  private val clients = Seq(client, clientWithFlush)

  private val clientFlushInterval = config.getInt("hbase.rpcs.buffered_flush_interval").toString().toShort
  val MaxRetryNum = config.getInt("max.retry.number")
  val MaxBackOff = config.getInt("max.back.off")
  val DeleteAllFetchSize = config.getInt("delete.all.fetch.size")

  /**
    * Serializer/Deserializer
    */
  def snapshotEdgeSerializer(snapshotEdge: SnapshotEdge) = new SnapshotEdgeSerializable(snapshotEdge)

  def indexEdgeSerializer(indexedEdge: IndexEdge) = new IndexEdgeSerializable(indexedEdge)

  def vertexSerializer(vertex: Vertex) = new VertexSerializable(vertex)

  val snapshotEdgeDeserializer = new SnapshotEdgeDeserializable
  val indexEdgeDeserializer = new IndexEdgeDeserializable
  val vertexDeserializer = new VertexDeserializable

  def getEdges(q: Query): Future[Seq[QueryResult]] = queryBuilder.getEdges(q)

  def checkEdges(params: Seq[(Vertex, Vertex, QueryParam)]): Future[Seq[QueryResult]] = {
    val futures = for {
      (srcVertex, tgtVertex, queryParam) <- params
    } yield queryBuilder.getEdge(srcVertex, tgtVertex, queryParam, false).toFuture

    Future.sequence(futures)
  }

  def getVertices(vertices: Seq[Vertex]): Future[Seq[Vertex]] = {
    def fromResult(queryParam: QueryParam,
                   kvs: Seq[org.hbase.async.KeyValue],
                   version: String): Option[Vertex] = {

      if (kvs.isEmpty) None
      else {
        val newKVs = kvs
        Option(vertexDeserializer.fromKeyValues(queryParam, newKVs, version, None))
      }
    }

    val futures = vertices.map { vertex =>
      val kvs = vertexSerializer(vertex).toKeyValues
      val get = new GetRequest(vertex.hbaseTableName.getBytes, kvs.head.row, vertexCf)
      //      get.setTimeout(this.singleGetTimeout.toShort)
      get.setFailfast(true)
      get.maxVersions(1)

      val cacheKey = MurmurHash3.stringHash(get.toString)
      val cacheVal = vertexCache.getIfPresent(cacheKey)
      if (cacheVal == null)
        client.get(get).toFutureWith(emptyKVs).map { kvs =>
          fromResult(QueryParam.Empty, kvs, vertex.serviceColumn.schemaVersion)
        }

      else Future.successful(cacheVal)
    }

    Future.sequence(futures).map { result => result.toList.flatten }
  }


  def mutateEdge(edge: Edge, withWait: Boolean): Future[Boolean] = {
    //    mutateEdgeWithOp(edge, withWait)
    val strongConsistency = edge.label.consistencyLevel == "strong"
    val edgeFuture =
      if (edge.op == GraphUtil.operations("delete") && !strongConsistency) {
        val zkQuorum = edge.label.hbaseZkAddr
        val (_, edgeUpdate) = Edge.buildDeleteBulk(None, edge)
        val mutations =
          mutationBuilder.indexedEdgeMutations(edgeUpdate) ++
            mutationBuilder.invertedEdgeMutations(edgeUpdate) ++
            mutationBuilder.increments(edgeUpdate)
        writeAsyncSimple(zkQuorum, mutations, withWait)
      } else {
        mutateEdgesInner(Seq(edge), strongConsistency, withWait)(Edge.buildOperation)
      }
    val vertexFuture = writeAsyncSimple(edge.label.hbaseZkAddr,
      mutationBuilder.buildVertexPutsAsync(edge), withWait)
    Future.sequence(Seq(edgeFuture, vertexFuture)).map { rets => rets.forall(identity) }
  }

  override def mutateEdges(edges: Seq[Edge], withWait: Boolean): Future[Seq[Boolean]] = {
    val edgeGrouped = edges.groupBy { edge => (edge.label, edge.srcVertex.innerId, edge.tgtVertex.innerId) } toSeq

    val ret = edgeGrouped.map { case ((label, srcId, tgtId), edges) =>
      if (edges.isEmpty) Future.successful(true)
      else {
        val head = edges.head
        val strongConsistency = head.label.consistencyLevel == "strong"

        if (strongConsistency) {
          val edgeFuture = mutateEdgesInner(edges, strongConsistency, withWait)(Edge.buildOperation)
          //TODO: decide what we will do on failure on vertex put
          val vertexFuture = writeAsyncSimple(head.label.hbaseZkAddr,
            mutationBuilder.buildVertexPutsAsync(head), withWait)
          Future.sequence(Seq(edgeFuture, vertexFuture)).map { rets => rets.forall(identity) }
        } else {
          Future.sequence(edges.map { edge =>
            mutateEdge(edge, withWait = withWait)
          }).map { rets =>
            rets.forall(identity)
          }
        }
      }
    }
    Future.sequence(ret)
  }

  def mutateVertex(vertex: Vertex, withWait: Boolean): Future[Boolean] = {
    if (vertex.op == GraphUtil.operations("delete")) {
      writeAsyncSimple(vertex.hbaseZkAddr, mutationBuilder.buildDeleteAsync(vertex), withWait)
    } else if (vertex.op == GraphUtil.operations("deleteAll")) {
      logger.info(s"deleteAll for vertex is truncated. $vertex")
      Future.successful(true) // Ignore withWait parameter, because deleteAll operation may takes long time
    } else {
      writeAsyncSimple(vertex.hbaseZkAddr, mutationBuilder.buildPutsAll(vertex), withWait)
    }
  }

  def incrementCounts(edges: Seq[Edge]): Future[Seq[(Boolean, Long)]] = {
    val defers: Seq[Deferred[(Boolean, Long)]] = for {
      edge <- edges
    } yield {
      val edgeWithIndex = edge.edgesWithIndex.head
      val countWithTs = edge.propsWithTs(LabelMeta.countSeq)
      val countVal = countWithTs.innerVal.toString().toLong
      val incr = mutationBuilder.buildIncrementsCountAsync(edgeWithIndex, countVal).head
      val request = incr.asInstanceOf[AtomicIncrementRequest]
      client.bufferAtomicIncrement(request) withCallback { resultCount: java.lang.Long =>
        (true, resultCount.longValue())
      } recoverWith { ex =>
        logger.error(s"mutation failed. $request", ex)
        (false, -1L)
      }
    }

    val grouped: Deferred[util.ArrayList[(Boolean, Long)]] = Deferred.groupInOrder(defers)
    grouped.toFuture.map(_.toSeq)
  }

  private def writeAsyncSimpleRetry(zkQuorum: String, elementRpcs: Seq[HBaseRpc], withWait: Boolean): Future[Boolean] = {
    def compute = writeAsyncSimple(zkQuorum, elementRpcs, withWait).flatMap { ret =>
      if (ret) Future.successful(ret)
      else throw FetchTimeoutException("writeAsyncWithWaitRetrySimple")
    }
    Extensions.retryOnFailure(MaxRetryNum) {
      compute
    } {
      logger.error(s"writeAsyncWithWaitRetrySimple: $elementRpcs")
      false
    }
  }

  private def writeToStorage(_client: HBaseClient, rpc: HBaseRpc): Deferred[Boolean] = {
    val defer = rpc match {
      case d: DeleteRequest => _client.delete(d)
      case p: PutRequest => _client.put(p)
      case i: AtomicIncrementRequest => _client.bufferAtomicIncrement(i)
    }
    defer withCallback { ret => true } recoverWith { ex =>
      logger.error(s"mutation failed. $rpc", ex)
      false
    }
  }

  private def writeAsyncSimple(zkQuorum: String, elementRpcs: Seq[HBaseRpc], withWait: Boolean): Future[Boolean] = {
    val _client = if (withWait) clientWithFlush else client
    if (elementRpcs.isEmpty) {
      Future.successful(true)
    } else {
      val defers = elementRpcs.map { rpc => writeToStorage(_client, rpc) }
      if (withWait)
        Deferred.group(defers).toFuture map { arr => arr.forall(identity) }
      else
        Future.successful(true)
    }
  }

  private def writeAsync(zkQuorum: String, elementRpcs: Seq[Seq[HBaseRpc]], withWait: Boolean): Future[Seq[Boolean]] = {
    val _client = if (withWait) clientWithFlush else client
    if (elementRpcs.isEmpty) {
      Future.successful(Seq.empty[Boolean])
    } else {
      val futures = elementRpcs.map { rpcs =>
        val defers = rpcs.map { rpc => writeToStorage(_client, rpc) }
        if (withWait)
          Deferred.group(defers).toFuture map { arr => arr.forall(identity) }
        else
          Future.successful(true)
      }
      if (withWait)
        Future.sequence(futures)
      else
        Future.successful(elementRpcs.map(_ => true))
    }
  }

  private def fetchInvertedAsync(edge: Edge): Future[(QueryParam, Option[Edge])] = {
    val labelWithDir = edge.labelWithDir
    val queryParam = QueryParam(labelWithDir)

    queryBuilder.getEdge(edge.srcVertex, edge.tgtVertex, queryParam, isInnerCall = true).toFuture map { queryResult =>
      (queryParam, queryResult.edgeWithScoreLs.headOption.map(_.edge))
    }
  }


  private def commitUpdate(snapshotEdgeOpt: Option[Edge], requestEdge: Edge, edgeMutate: EdgeMutate): Future[Int] = {
    val currentTs = requestEdge.ts

    val queryParam = QueryParam(requestEdge.labelWithDir)
    val version = requestEdge.label.schemaVersion
    //    def fetchKeyValue(putRequest: PutRequest) = {
    //      val get = new GetRequest(putRequest.table(), putRequest.key())
    //      val kv = client.get(get).join(1000).get(0)
    //      kv
    //    }
    def toPutRequest(sn: SnapshotEdge): PutRequest = {
      val rpc = mutationBuilder.buildPutAsync(sn).head
      val kv = snapshotEdgeSerializer(sn).toKeyValues.head
      val kv2 = snapshotEdgeSerializer(snapshotEdgeDeserializer.fromKeyValues(queryParam, Seq(kv), version, None)).toKeyValues.head
      //      if (Bytes.compareTo(kv.value, kv2.value) != 0) {
      //        logger.error(s"!!!!!!!!!!!!!!!!!!\n$kv\n$kv2\n!!!!!!!!!!!!!!!!!!!1")
      //        throw new Exception("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")
      //      }
      val putRequest = rpc.asInstanceOf[PutRequest]
      putRequest
    }
    def oldBytes = snapshotEdgeOpt match {
      case None => Array.empty[Byte]
      case Some(snapshotEdge) => toPutRequest(snapshotEdge.toSnapshotEdge).value()
    }
    def lockEdge = requestEdge.toSnapshotEdge.copy(lockTs = Option(currentTs))

    def debug(): String = {
      List(s"Me: ${requestEdge.ts}",
        s"SnapshotEdge: ${snapshotEdgeOpt.map(_.toLogString)}",
        s"RequestEdge: ${requestEdge.toLogString}",
        s"EdgeMutate: ${edgeMutate}"
      ).mkString("\n")
    }

    def acquireLock: Future[Int] = {
      client.compareAndSet(toPutRequest(lockEdge), oldBytes).toFuture.map { ret =>
        logger.info(s"${debug()}")
        if (!ret) {
          logger.info(s"AcquireLock Failed. $ret, $currentTs")
          logger.info(s"Old: ${oldBytes.toList}")
          logger.info(s"New: ${toPutRequest(lockEdge).value().toList}")
        } else {
          logger.error(s"AcquireLock Success.: $currentTs")
          logger.error(s"AcquireLock Edge.: ${requestEdge.toLogString}")
        }
        if (ret.booleanValue()) 0 else -1
      }
    }

    def releaseLock: Future[Int] = {
      val releaseLockEdge = edgeMutate.newInvertedEdge match {
        case None =>
          // self retrying
          snapshotEdgeOpt match {
            case None => throw new Exception("Not reachable !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!.")
            case Some(snapshotEdge) =>
              snapshotEdge.toSnapshotEdge.copy(lockTs = None)
          }
        case Some(newSnapshotEdge) =>
          // empty snapshot before.
          snapshotEdgeOpt match {
            // self retrying
            case None => // there is no old snpahotedge, requestEdge success.
              newSnapshotEdge.copy(lockTs = None)
            case Some(oldSnapshotEdge) => // others is retrying.
              newSnapshotEdge.copy(lockTs = None)
          }
      }

      client.compareAndSet(toPutRequest(releaseLockEdge), toPutRequest(lockEdge).value()).recoverWith { ex: Exception =>
        logger.error(s"ReleaseLock RPC Failed.")
        false
      }.toFuture.map { ret =>
        logger.info(s"${debug()}")
        //        val fetchedKV = fetchKeyValue(toPutRequest(lockEdge))
        //        val putRequest = toPutRequest(releaseLockEdge)

        //        val sn = snapshotEdgeDeserializer.fromKeyValues(queryParam, Seq(fetchKeyValue(toPutRequest(lockEdge))), version, None)
        //        if (putRequest.timestamp() <= fetchedKV.timestamp()) logger.error(s"on success with LockTS in hbase: ${sn.lockTs}, fetchedTs: ${fetchedKV.timestamp()}, putRequestTs: ${putRequest.timestamp()}")
        logger.info(s"lockTs: ${releaseLockEdge.lockTs}, -> $currentTs")

        if (!ret) {
          logger.error(s"ReleaseLock Failed, $currentTs")
          //          logger.info(s"ExitInHBase: ${fetchKeyValue(toPutRequest(lockEdge)).value().toList}")
          logger.info(s"Expected: ${toPutRequest(lockEdge).value().toList}")
          logger.info(s"Same: ${toPutRequest(releaseLockEdge).value().toList}")
        } else {
          logger.info(s"ReleaseLock Success, $currentTs")
        }
        if (ret.booleanValue()) 0 else -1
      }
    }

    def indexedEdgeMutationsFuture: Future[Int] = {
      writeAsyncSimple(requestEdge.label.hbaseZkAddr, mutationBuilder.indexedEdgeMutations(edgeMutate), withWait = true).map(ret => if (ret) 0 else -1)
    }
    def indexedEdgeIncrementsFuture: Future[Int] = {
      writeAsyncSimple(requestEdge.label.hbaseZkAddr, mutationBuilder.increments(edgeMutate), withWait = true).map(ret => if (ret) 0 else -1)
    }


    logger.error(s"snapshotEdge: ${snapshotEdgeOpt}")
    // safe guard.
    snapshotEdgeOpt match {
      case None => // no one succes acquire lock.
        // step 1. acquireLock
        // step 2. mutateIndexEdges
        // step 3. releaseLock
        // step 4. incrementIndexEdges
        for {
          locked <- acquireLock
          mutationSuccess <- if (locked != 0) Future.successful(100) else indexedEdgeMutationsFuture
          lockReleased <- if (mutationSuccess != 0) Future.successful(1) else releaseLock
          incrementSuccess <- if (lockReleased != 0) Future.successful(2) else indexedEdgeIncrementsFuture
        } yield {
          if (incrementSuccess != 0) 3 else 0
        }

      case Some(snapshotEdge) =>
        snapshotEdge.lockTs match {
          case None => // there is at least one success. but there is no lock.
            // step1. acquire lock.
            // step 2. mutateIndexEdges
            // step 3. releaseLock
            // step 4. incrementIndexEdges

            for {
              locked <- acquireLock
              mutationSuccess <- if (locked != 0) Future.successful(4) else indexedEdgeMutationsFuture
              lockReleased <- if (mutationSuccess != 0) Future.successful(5) else releaseLock
              incrementSuccess <- if (lockReleased != 0) Future.successful(6) else indexedEdgeIncrementsFuture
            } yield {
              if (incrementSuccess != 0) 7 else 0
            }

          case Some(oldLockTs) =>
            val snapshotKv = snapshotEdgeSerializer(snapshotEdge.toSnapshotEdge).toKeyValues.head
            val requestKv = snapshotEdgeSerializer(requestEdge.toSnapshotEdge).toKeyValues.head

            if (oldLockTs == currentTs && requestKv.value.toList == snapshotKv.value.toList) {
              logger.error("retry in same edge")

              // lock is mine
              // step 2. mutateIndexEdges
              // step 3. releaseLock
              // step 4. incrementIndexEdges
              for {
                mutationSuccess <- indexedEdgeMutationsFuture
                lockReleased <- if (mutationSuccess != 0) Future.successful(8) else releaseLock
                incrementSuccess <- if (lockReleased != 0) Future.successful(9) else indexedEdgeIncrementsFuture
              } yield {
                if (incrementSuccess != 0) 10 else 0
              }
            } else {
              // lock is others
              logger.error(s"$oldLockTs")
              Future.successful(11)
            }
        }
    }
  }

  private def mutateEdgesInner(edges: Seq[Edge],
                               checkConsistency: Boolean,
                               withWait: Boolean)(f: (Option[Edge], Seq[Edge]) => (Edge, EdgeMutate)): Future[Boolean] = {

    if (!checkConsistency) {
      val zkQuorum = edges.head.label.hbaseZkAddr
      val futures = edges.map { edge =>
        val (_, edgeUpdate) = f(None, Seq(edge))
        val mutations =
          mutationBuilder.indexedEdgeMutations(edgeUpdate) ++
            mutationBuilder.invertedEdgeMutations(edgeUpdate) ++
            mutationBuilder.increments(edgeUpdate)
        writeAsyncSimple(zkQuorum, mutations, withWait)
      }
      Future.sequence(futures).map { rets => rets.forall(identity) }
    } else {
      def compute = fetchInvertedAsync(edges.head) flatMap { case (queryParam, snapshotEdgeOpt) =>
        val (newEdge, edgeMutate) = f(snapshotEdgeOpt, edges)

        //        logger.error(s"$snapshotEdgeOpt\n$newEdge\n$edgeMutate\n")
        val requestEdge = newEdge
        commitUpdate(snapshotEdgeOpt, requestEdge, edgeMutate).map { allSuccess =>
          if (allSuccess != 0) {
            throw new RuntimeException(s"commitUpdateRetry, $allSuccess, ${requestEdge.ts}, ts: ${System.currentTimeMillis()}")
          } else {
            logger.info(s"commitUpdate Success. ${requestEdge.ts}, ts: ${System.currentTimeMillis()}")
          }
          allSuccess == 0
        }
      }
      Extensions.retryOnFailure(MaxRetryNum) {
        compute
      } {
        logger.error(s"mutate failed after $MaxRetryNum retry")
        edges.foreach { edge => ExceptionHandler.enqueue(ExceptionHandler.toKafkaMessage(element = edge)) }
        false
      }
    }
  }


  def mutateLog(snapshotEdgeOpt: Option[Edge], edges: Seq[Edge],
                newEdge: Edge, edgeMutate: EdgeMutate) = {
    Seq("----------------------------------------------",
      s"SnapshotEdge: ${snapshotEdgeOpt.map(_.toLogString)}",
      s"requestEdges: ${edges.map(_.toLogString).mkString("\n")}",
      s"newEdge: ${newEdge.toLogString}",
      s"mutation: \n${edgeMutate.toLogString}",
      "----------------------------------------------").mkString("\n")
  }

  private def deleteAllFetchedEdgesAsyncOld(queryResult: QueryResult,
                                            requestTs: Long,
                                            retryNum: Int): Future[Boolean] = {
    val queryParam = queryResult.queryParam
    val zkQuorum = queryParam.label.hbaseZkAddr
    val futures = for {
      edgeWithScore <- queryResult.edgeWithScoreLs
      (edge, score) = EdgeWithScore.unapply(edgeWithScore).get
    } yield {
      /** reverted direction */
      val reversedIndexedEdgesMutations = edge.duplicateEdge.edgesWithIndex.flatMap { indexedEdge =>
        mutationBuilder.buildDeletesAsync(indexedEdge) ++ mutationBuilder.buildIncrementsAsync(indexedEdge, -1L)
      }
      val reversedSnapshotEdgeMutations = mutationBuilder.buildDeleteAsync(edge.toSnapshotEdge)
      val forwardIndexedEdgeMutations = edge.edgesWithIndex.flatMap { indexedEdge =>
        mutationBuilder.buildDeletesAsync(indexedEdge) ++ mutationBuilder.buildIncrementsAsync(indexedEdge, -1L)
      }
      val mutations = reversedIndexedEdgesMutations ++ reversedSnapshotEdgeMutations ++ forwardIndexedEdgeMutations
      writeAsyncSimple(zkQuorum, mutations, withWait = true)
    }

    Future.sequence(futures).map { rets => rets.forall(identity) }
  }

  private def buildEdgesToDelete(queryResult: QueryResult, requestTs: Long): QueryResult = {
    val edgeWithScoreLs = queryResult.edgeWithScoreLs.filter { edgeWithScore =>
      (edgeWithScore.edge.ts < requestTs) && !edgeWithScore.edge.propsWithTs.containsKey(LabelMeta.degreeSeq)
    }.map { edgeWithScore =>
      val copiedEdge = edgeWithScore.edge.copy(op = GraphUtil.operations("delete"), ts = requestTs, version = requestTs)
      edgeWithScore.copy(edge = copiedEdge)
    }
    queryResult.copy(edgeWithScoreLs = edgeWithScoreLs)
  }

  private def deleteAllFetchedEdgesLs(queryResultLs: Seq[QueryResult], requestTs: Long): Future[(Boolean, Boolean)] = {
    queryResultLs.foreach { queryResult =>
      if (queryResult.isFailure) throw new RuntimeException("fetched result is fallback.")
    }

    val futures = for {
      queryResult <- queryResultLs
      deleteQueryResult = buildEdgesToDelete(queryResult, requestTs) if deleteQueryResult.edgeWithScoreLs.nonEmpty
    } yield {
      queryResult.queryParam.label.schemaVersion match {
        case HBaseType.VERSION3 =>

          /**
            * read: snapshotEdge on queryResult = O(N)
            * write: N x (relatedEdges x indices(indexedEdge) + 1(snapshotEdge))
            */
          mutateEdges(deleteQueryResult.edgeWithScoreLs.map(_.edge), withWait = true).map { rets => rets.forall(identity) }
        case _ =>

          /**
            * read: x
            * write: N x ((1(snapshotEdge) + 2(1 for incr, 1 for delete) x indices)
            */
          deleteAllFetchedEdgesAsyncOld(queryResult, requestTs, MaxRetryNum)
      }
    }
    if (futures.isEmpty) {
      // all deleted.
      Future.successful(true -> true)
    } else {
      Future.sequence(futures).map { rets => false -> rets.forall(identity) }
    }
  }

  def fetchAndDeleteAll(query: Query, requestTs: Long): Future[(Boolean, Boolean)] = {
    val future = for {
      queryResultLs <- getEdges(query)
      (allDeleted, ret) <- deleteAllFetchedEdgesLs(queryResultLs, requestTs)
    } yield {
      (allDeleted, ret)
    }
    Extensions.retryOnFailure(MaxRetryNum) {
      future
    } {
      logger.error(s"fetch and deleteAll failed.")
      (true, false)
    }

  }

  def deleteAllAdjacentEdges(srcVertices: List[Vertex],
                             labels: Seq[Label],
                             dir: Int,
                             ts: Long): Future[Boolean] = {
    val requestTs = ts
    val queryParams = for {
      label <- labels
    } yield {
      val labelWithDir = LabelWithDirection(label.id.get, dir)
      QueryParam(labelWithDir).limit(0, DeleteAllFetchSize).duplicatePolicy(Option(Query.DuplicatePolicy.Raw))
    }

    val step = Step(queryParams.toList)
    val q = Query(srcVertices, Vector(step))

    //    Extensions.retryOnSuccessWithBackoff(MaxRetryNum, Random.nextInt(MaxBackOff) + 1) {
    Extensions.retryOnSuccess(MaxRetryNum) {
      fetchAndDeleteAll(q, requestTs)
    } { case (allDeleted, deleteSuccess) =>
      allDeleted && deleteSuccess
    }.map { case (allDeleted, deleteSuccess) => allDeleted && deleteSuccess }
  }

  def flush(): Unit = clients.foreach { client =>
    val timeout = Duration((clientFlushInterval + 10) * 20, duration.MILLISECONDS)
    Await.result(client.flush().toFuture, timeout)
  }

}
