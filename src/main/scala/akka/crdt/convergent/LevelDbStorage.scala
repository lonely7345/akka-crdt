/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.crdt.convergent

import scala.util.Try
import scala.reflect.ClassTag
import scala.collection.immutable
import play.api.libs.json.Json.parse
import play.api.libs.json.JsValue
import akka.event.LoggingAdapter
import org.iq80.leveldb.{ Logger, ReadOptions, WriteOptions, Options, CompressionType, WriteBatch, DB, DBFactory }
import org.iq80.leveldb.impl.Iq80DBFactory
import org.fusesource.leveldbjni.JniDBFactory
import org.fusesource.leveldbjni.JniDBFactory.{ asString, bytes }
import java.io.File

class LevelDbStorage(
  val nodename: String,
  val settings: ConvergentReplicatedDataTypeSettings,
  log: LoggingAdapter) extends Storage { storage ⇒
  import settings._

  val filename = s"$LevelDbStoragePath/$nodename"

  private val levelDbReadOptions: ReadOptions = new ReadOptions().verifyChecksums(LevelDbVerifyChecksums)
  private val levelDbWriteOptions: WriteOptions = new WriteOptions().sync(LevelDbUseFsync)

  private val factory: DBFactory = if (LevelDbUseNative) JniDBFactory.factory else Iq80DBFactory.factory

  private val leveldbOptions: Options = {
    val options = new Options()
      .createIfMissing(true)
      .cacheSize(LevelDbCacheSize)
      .logger(new Logger() { def log(message: String) = storage.log.debug(message) })
    if (LevelDbUseNative) options.compressionType(CompressionType.SNAPPY)
    else options.compressionType(CompressionType.NONE)
  }

  private def createDb(filename: String): DB = {
    log.info("Creating new LevelDB storage")
    factory.open(new File(filename), leveldbOptions)
  }

  private val db: DB = createDb(filename)

  def findById[T <: ConvergentReplicatedDataType: ClassTag](id: String): Try[T] = Try {
    val clazz = implicitly[ClassTag[T]].runtimeClass
    val crdt =
      if (classOf[GCounter].isAssignableFrom(clazz)) {
        toJson(getElementInDb(GCounter.crdtType, id)).as[GCounter]
      } else if (classOf[PNCounter].isAssignableFrom(clazz)) {
        toJson(getElementInDb(PNCounter.crdtType, id)).as[PNCounter]
      } else if (classOf[GSet].isAssignableFrom(clazz)) {
        toJson(getElementInDb(GSet.crdtType, id)).as[GSet]
      } else if (classOf[TwoPhaseSet].isAssignableFrom(clazz)) {
        toJson(getElementInDb(TwoPhaseSet.crdtType, id)).as[TwoPhaseSet]
      } else throw new ClassCastException(s"Could create new CvRDT with id [$id] and type [$clazz]")
    log.debug("Finding CvRDT in LevelDB: {}", crdt)
    crdt.asInstanceOf[T]
  }

  def store(crdt: ConvergentReplicatedDataType): ConvergentReplicatedDataType = {
    log.debug("Storing CvRDT in LevelDB: {}", crdt)
    db.put(bytes(createKey(crdt.crdtType, crdt.id)), bytes(crdt.toString), levelDbWriteOptions)
    crdt
  }

  /**
   * Store a batch.
   */
  def store(crdts: immutable.Seq[ConvergentReplicatedDataType]): immutable.Seq[ConvergentReplicatedDataType] = {
    if (!crdts.isEmpty) {
      log.debug("Storing batch in LevelDB: {}", crdts.mkString(", "))
      val batch = db.createWriteBatch()
      try {
        crdts foreach { crdt ⇒ batch put (bytes(crdt.crdtType + "/" + crdt.id), bytes(crdt.toString)) }
        db.write(batch, levelDbWriteOptions)
      } finally batch.close()
    }
    crdts
  }

  override def close(): Unit = {
    log.info("Closing LevelDB storage")
    db.close()
  }

  override def destroy(): Unit = if (LevelDbDestroyOnShutdown) {
    log.info("Destroying LevelDB storage(s)")
    factory.destroy(new File(filename), new Options)
  }

  def exists(id: String): Boolean = db.get(bytes(id)) ne null

  private def getElementInDb(crdtType: String, id: String): Array[Byte] = {
    val result = db.get(bytes(createKey(crdtType, id)))
    if (result eq null) throw new StorageException(s"Element with id = '$id' does not exists in database")
    result
  }

  private def createKey(crdtType: String, id: String): String = crdtType + "/" + id

  private def toJson(bytes: Array[Byte]): JsValue = parse(asString(bytes))
}
