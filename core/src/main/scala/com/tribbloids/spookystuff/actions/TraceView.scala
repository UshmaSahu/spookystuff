package com.tribbloids.spookystuff.actions

import org.slf4j.LoggerFactory
import com.tribbloids.spookystuff.entity.PageRow
import com.tribbloids.spookystuff.pages.{Page, PageLike, PageUtils}
import com.tribbloids.spookystuff.session.{DriverSession, NoDriverSession, Session}
import com.tribbloids.spookystuff.utils.Utils
import com.tribbloids.spookystuff.{RemoteDisabledException, dsl, Const, SpookyContext}

import scala.collection.mutable.ArrayBuffer
import scala.reflect.ClassTag

/**
 * Created by peng on 10/25/14.
 */
class TraceView(
                 override val self: Seq[Action]
                 ) extends Actions(self) { //remember trace is not a block! its the super container that cannot be wrapped

  //always has output (Sometimes Empty) to handle left join
  override def doInterpolate(pr: PageRow): Option[this.type] = {
    val seq = this.doInterpolateSeq(pr)

    Some(new TraceView(seq).asInstanceOf[this.type])
  }

  override def apply(session: Session): Seq[PageLike] = {

    val results = new ArrayBuffer[PageLike]()

    this.self.foreach {
      action =>
        val result = action.apply(session)
        session.backtrace ++= action.trunk

        if (action.hasOutput) {

          results ++= result
          session.spooky.metrics.pagesFetchedFromWeb += result.count(_.isInstanceOf[Page])

          val spooky = session.spooky

          if (spooky.conf.autoSave) result.foreach{
            case page: Page => page.autoSave(spooky)
            case _ =>
          }
          if (spooky.conf.cacheWrite) PageUtils.autoCache(result, spooky)
        }
        else {
          assert(result.isEmpty)
        }
    }

    results
  }

  lazy val dryrun: DryRun = {
    val result: ArrayBuffer[Trace] = ArrayBuffer()

    for (i <- self.indices) {
      val selfi = self(i)
      if (selfi.hasOutput){
        val backtrace = selfi match {
          case dl: Driverless => selfi :: Nil
          case _ => self.slice(0, i).flatMap(_.trunk) :+ selfi
        }
        result += backtrace
      }
    }

    result
  }

  //invoke before interpolation!
  def autoSnapshot: Trace = {
    if (this.hasOutput && self.nonEmpty) self
    else self :+ Snapshot() //Don't use singleton, otherwise will flush timestamp and name
  }

  def fetch(spooky: SpookyContext): Seq[PageLike] = {

    val results = Utils.retry (Const.remoteResourceLocalRetries){
      fetchOnce(spooky)
    }
    val numPages = results.count(_.isInstanceOf[Page])
    spooky.metrics.pagesFetched += numPages
    results
  }

  def fetchOnce(spooky: SpookyContext): Seq[PageLike] = {

    if (!this.hasOutput) return Nil

    val pagesFromCache = if (!spooky.conf.cacheRead) Seq(null)
    else dryrun.map(dry => PageUtils.autoRestore(dry, spooky))

    if (!pagesFromCache.contains(null)){
      val results = pagesFromCache.flatten
      spooky.metrics.pagesFetchedFromCache += results.count(_.isInstanceOf[Page])
      this.self.foreach{
        action =>
          LoggerFactory.getLogger(this.getClass).info(s"(cached)+> ${action.toString}")
      }

      results
    }
    else {
      if (!spooky.conf.remote) throw new RemoteDisabledException(
        "Resource is not cached and not allowed to be fetched remotely, " +
          "the later can be enabled by setting SpookyContext.conf.remote=true"
      )

      val session = if (self.count(_.needDriver) == 0) new NoDriverSession(spooky)
      else new DriverSession(spooky)
      try {
        val result = this.apply(session)
        spooky.metrics.fetchSuccess += 1
        result
      }
      catch {
        case e: Throwable =>
          spooky.metrics.fetchFailure += 1
          throw e
      }
      finally {
        session.close()
      }
    }
  }

  //the minimal equivalent action that can be put into backtrace
  override def trunk = Some(new TraceView(this.trunkSeq).asInstanceOf[this.type])
}

//The precedence of an inﬁx operator is determined by the operator’s ﬁrst character.
//Characters are listed below in increasing order of precedence, with characters on
//the same line having the same precedence.
//(all letters)
//|
//^
//&
//= !.................................................(new doc)
//< >
//= !.................................................(old doc)
//:
//+ -
//* / %
//(all other special characters)
//now using immutable pattern to increase maintainability
//put all narrow transformation closures here
final class TraceSetView(self: Set[Trace]) {

  import dsl._

  //one-to-one
  def +>(another: Action): Set[Trace] = self.map(trace => trace :+ another)
  def +>(others: TraversableOnce[Action]): Set[Trace] = self.map(trace => trace ++ others)

  //one-to-one truncate longer
  def +>(others: Iterable[Trace]): Set[Trace] = self.zip(others).map(tuple => tuple._1 ++ tuple._2)

  //one-to-many

  def *>[T: ClassTag](others: TraversableOnce[T]): Set[Trace] = self.flatMap(
    trace => others.map {
      case otherAction: Action => trace :+ otherAction
      case otherTrace: Trace => trace ++ otherTrace
    }
  )

  def ||(other: TraversableOnce[Trace]): Set[Trace] = self ++ other

  def autoSnapshot: Set[Trace] = self.map(_.autoSnapshot)

  def interpolate(row: PageRow): Set[Trace] = self.flatMap(_.interpolate(row).map(_.self))

  def outputNames: Set[String] = self.map(_.outputNames).reduce(_ ++ _)
}