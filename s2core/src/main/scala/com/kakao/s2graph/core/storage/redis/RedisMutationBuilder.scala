package com.kakao.s2graph.core.storage.redis

import com.kakao.s2graph.core._
import com.kakao.s2graph.core.storage.{SKeyValue, MutationBuilder}
import com.kakao.s2graph.core.utils.logger
import org.apache.hadoop.hbase.util.Bytes

import scala.concurrent.ExecutionContext

/**
 * @author Junki Kim (wishoping@gmail.com) and Hyunsung Jo (hyunsung.jo@gmail.com) on 2016/Jan/07.
 */
class RedisMutationBuilder(storage: RedisStorage)(implicit ec: ExecutionContext)
  extends MutationBuilder[RedisRPC](storage) {

  def put(kvs: Seq[SKeyValue]): Seq[RedisRPC] =
    kvs.map { kv => new RedisPutRequest(kv.row, kv.qualifier, kv.value, kv.timestamp) }

  def toHex(b: Array[Byte]): String = {
    val tmp = b.map("%02x".format(_)).mkString("\\x")
    if ( tmp.isEmpty ) "" else "\\x" + tmp
  }

  def incrementCount(kvs: Seq[SKeyValue]): Seq[RedisRPC] =
    kvs.map { kv =>
      val offset = kv.value.length - 8
      logger.info(s">> [incrementCount] len: ${kv.value.length} value : ${toHex(kv.value)}, offset: $offset")
      new RedisAtomicIncrementRequest(kv.row, kv.value, Bytes.toLong(kv.value, offset, 8), isDegree = false)
    }

  def increment(kvs: Seq[SKeyValue]): Seq[RedisRPC] =
    kvs.map { kv =>
      val offset = kv.value.length - 8
      logger.info(s">> [increment] len: ${kv.value.length} value : ${toHex(kv.value)}, offset: $offset")
      new RedisAtomicIncrementRequest(kv.row, kv.value, Bytes.toLong(kv.value, offset, 8), isDegree = true)
    }


  def delete(kvs: Seq[SKeyValue]): Seq[RedisRPC] =
    kvs.map { kv =>
      if (kv.qualifier == null) new RedisDeleteRequest(kv.row, kv.value, kv.timestamp)
      else new RedisDeleteRequest(kv.row, kv.value, kv.timestamp)
    }

  /** Vertex */
  def buildPutsAsync(vertex: Vertex): Seq[RedisRPC] = {
    val kvs = storage.vertexSerializer(vertex).toKeyValues
    put(kvs)
  }

  def buildDeleteBelongsToId(vertex: Vertex): Seq[RedisRPC] = {
    val kvs = storage.vertexSerializer(vertex).toKeyValues
    val kv = kvs.head

    import org.apache.hadoop.hbase.util.Bytes
    val newKVs = vertex.belongLabelIds.map { id =>
      kv.copy(qualifier = Bytes.toBytes(Vertex.toPropKey(id)))
    }
    delete(newKVs)
  }

  def increments(edgeMutate: EdgeMutate): Seq[RedisRPC] = {
    (edgeMutate.edgesToDelete.isEmpty, edgeMutate.edgesToInsert.isEmpty) match {
      case (true, true) =>

        /** when there is no need to update. shouldUpdate == false */
        List.empty[RedisAtomicIncrementRequest]
      case (true, false) =>

        logger.info(s">> [increments] new edges and increase degree ")
        /** no edges to delete but there is new edges to insert so increase degree by 1 */
        edgeMutate.edgesToInsert.flatMap { e => buildIncrementsAsync(e) }
      case (false, true) =>

        logger.info(s">> [increments] delete edges and increase degree ")
        /** no edges to insert but there is old edges to delete so decrease degree by 1 */
        edgeMutate.edgesToDelete.flatMap { e => buildIncrementsAsync(e, -1L) }
      case (false, false) =>

        /** update on existing edges so no change on degree */
        List.empty[RedisAtomicIncrementRequest]
    }
  }


  def buildDeleteAsync(snapshotEdge: SnapshotEdge): Seq[RedisRPC] =
    delete(storage.snapshotEdgeSerializer(snapshotEdge).toKeyValues)

  def buildDeleteAsync(vertex: Vertex): Seq[RedisRPC] = {
    val kvs = storage.vertexSerializer(vertex).toKeyValues
    val kv = kvs.head
    delete(Seq(kv.copy(qualifier = null)))
  }

  def buildVertexPutsAsync(edge: Edge): Seq[RedisRPC] =
    if (edge.op == GraphUtil.operations("delete"))
      buildDeleteBelongsToId(edge.srcForVertex) ++ buildDeleteBelongsToId(edge.tgtForVertex)
    else
      buildPutsAsync(edge.srcForVertex) ++ buildPutsAsync(edge.tgtForVertex)

  def buildDeletesAsync(indexedEdge: IndexEdge): Seq[RedisRPC] =
    delete(storage.indexEdgeSerializer(indexedEdge).toKeyValues)

  /** IndexEdge */
  def buildIncrementsAsync(indexedEdge: IndexEdge, amount: Long): Seq[RedisRPC] = {
    logger.info(s"<< [RedisMutationBuilder.buildIncrementsAsync] ")
    storage.indexEdgeSerializer(indexedEdge).toKeyValues.headOption match {
      case None => Nil
      case Some(kv) =>
        val zeroLenBytes = Array.fill[Byte](1)(0)
        val copiedKV = kv.copy(qualifier = Array.empty[Byte], value = Bytes.add(zeroLenBytes, Bytes.toBytes(amount)))
        increment(Seq(copiedKV))
    }
  }

  def snapshotEdgeMutations(edgeMutate: EdgeMutate): Seq[RedisRPC] =
    edgeMutate.newSnapshotEdge.map(e => buildPutAsync(e)).getOrElse(Nil)

  def buildIncrementsCountAsync(indexedEdge: IndexEdge, amount: Long): Seq[RedisRPC] =
    storage.indexEdgeSerializer(indexedEdge).toKeyValues.headOption match {
      case None => Nil
      case Some(kv) =>
        val oneLenBytes = Array.fill[Byte](1)(1.toByte)
        val copiedKV = kv.copy(value = Bytes.add(oneLenBytes, Bytes.toBytes(amount)))
        incrementCount(Seq(copiedKV))
    }

  def buildPutsAsync(indexedEdge: IndexEdge): Seq[RedisRPC] = {
    logger.info(s"<< [RedisMutationBuilder.buildPutsAsync] enter")
    logger.info(s"\t<< [RedisMutationBuilder.buildPutsAsync] indexedEdge(${indexedEdge.labelIndex.name}, ${indexedEdge.labelIndexSeq}) : src[${indexedEdge.srcVertex}] -> tgt[${indexedEdge.tgtVertex}]")
    val t = storage.indexEdgeSerializer(indexedEdge).toKeyValues
    logger.info(s"\t<< [RedisMutationBuilder.buildPutsAsync] kvs : ${t.length}")
    put(t)
  }

  /** EdgeMutate */
  def indexedEdgeMutations(edgeMutate: EdgeMutate): Seq[RedisRPC] = {
    logger.info(s"<< [indexedEdgeMutations] enter")
    val deleteMutations = edgeMutate.edgesToDelete.flatMap(edge => buildDeletesAsync(edge))
    val insertMutations = edgeMutate.edgesToInsert.flatMap(edge => buildPutsAsync(edge))

    deleteMutations ++ insertMutations
  }

  /** SnapshotEdge */
  def buildPutAsync(snapshotEdge: SnapshotEdge): Seq[RedisRPC] = {
    logger.info(s">> buildPutAsync")
    put(storage.snapshotEdgeSerializer(snapshotEdge).toKeyValues)
  }

}