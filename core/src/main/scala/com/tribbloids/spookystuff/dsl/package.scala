package com.tribbloids.spookystuff

import java.util.Date

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.DataFrame
import com.tribbloids.spookystuff.actions._
import com.tribbloids.spookystuff.entity.PageRow
import com.tribbloids.spookystuff.expressions._
import com.tribbloids.spookystuff.pages.{Elements, Page, PageUID, Unstructured}
import com.tribbloids.spookystuff.sparkbinding.{DataFrameView, PageRowRDD, StringRDDView}
import com.tribbloids.spookystuff.utils.Default

import scala.collection.immutable.ListSet
import scala.collection.{GenTraversableOnce, IterableLike}
import scala.language.implicitConversions
import scala.reflect.ClassTag

/**
 * Created by peng on 9/18/14.
 */
package object dsl {

  //  type SerializableCookie = Cookie with Serializable

  implicit def PageRowRDDToSelf(wrapper: PageRowRDD): RDD[PageRow] = wrapper.self

  implicit def spookyContextToPageRowRDD(spooky: SpookyContext): PageRowRDD =
    new PageRowRDD(spooky.sqlContext.sparkContext.parallelize(Seq(PageRow())), spooky = spooky.getContextForNewInput)

  implicit def traceView(trace: Trace): TraceView = new TraceView(trace)

  implicit def traceSetView[Repr](traces: Repr)(implicit f: Repr => Set[Trace]): TraceSetView = new TraceSetView(traces)

  implicit def actionToTraceSet(action: Action): Set[Trace] = Set(Seq(action))

  //------------------------------------------------------------

  //this hierarchy aims to create a short DSL for selecting components from PageRow, e.g.:
  //'abc:  cells with key "abc", tempkey precedes ordinary key
  //'abc.$("div#a1"): all children of an unstructured field (either a page or element) that match the selector
  //$("div#a1"): all children of the only page that match the selector, if multiple page per row, throws an exception
  //$_*("div#a1"): all children of all pages that match the selector.
  //'abc.$("div#a1").head: first child of an unstructured field (either a page or element) that match the selector
  //'abc.$("div#a1").text: first text of an unstructured field that match the selector
  //'abc.$("div#a1").texts: all texts of an unstructured field that match the selector
  //'abc.$("div#a1").attr("src"): first "src" attribute of an unstructured field that match the selector
  //'abc.$("div#a1").attrs("src"): first "src" attribute of an unstructured field that match the selector

  def S(selector: String): FindAllExpr = GetOnlyPageExpr.findAll(selector)
  def S(selector: String, i: Int): Expression[Unstructured] = {
    val expr = GetOnlyPageExpr.findAll(selector)
    new IterableLikeExprView(expr).get(i)
  }
  def S: Expression[Page] = GetOnlyPageExpr

  def S_*(selector: String): FindAllExpr = GetAllPagesExpr.findAll(selector)
  def S_*(selector: String, i: Int): Expression[Unstructured] = {
    val expr = GetAllPagesExpr.findAll(selector)
    new IterableLikeExprView(expr).get(i)
  }
  def `S_*`: Expression[Elements[Page]] = GetAllPagesExpr

  def A(selector: String): FindAllExpr = 'A.findAll(selector)
  def A(selector: String, i: Int): Expression[Unstructured] = {
    val expr = 'A.findAll(selector)
    new IterableLikeExprView(expr).get(i)
  }

  implicit class ExprView[+T: ClassTag](self: Expression[T]) extends Serializable {

    private def defaultVal: T = Default.value[T]

    def andMap[A](g: T => A): Expression[A] = self.andThen(_.map(v => g(v)))

    def andMap[A](g: T => A, name: String): Expression[A] = self.andThen(NamedFunction1(_.map(v => g(v)), name))

    def andFlatMap[A](g: T => Option[A]): Expression[A] = self.andThen(_.flatMap(v => g(v)))

    def andFlatMap[A](g: T => Option[A], name: String): Expression[A] = self.andThen(NamedFunction1(_.flatMap(v => g(v)), name))

    def typed[A](implicit ev: ClassTag[A]) = this.andFlatMap[A](
      {
        case res: A => Some(res)
        case _ => None
      }: T => Option[A],
      s"filterByType[${ev.toString()}}]"
    )

    def toStr = this.andMap(_.toString)

    def into(name: Symbol): Expression[Traversable[T]] = new InsertIntoExpr[T](name.name, self)
    def ~+(name: Symbol) = into(name)

    //these will convert Expression to a common function
    def getOrElse[B >: T](value: =>B = defaultVal): NamedFunction1[PageRow, B] = self.andThen(
      NamedFunction1(_.getOrElse(value), s"getOrElse($value)")
    )

    def orDefault[B >: T]() = orElse(Some(defaultVal))

    def orElse[B >: T](valueOption: =>Option[B]): Expression[B] = self.andThen(
      NamedFunction1(_.orElse(valueOption), s"orElse($valueOption)")
    )

    def orElse[B >: T](expr: Expression[B]): Expression[B] = new Expression[B] {

      override val name: String = s"$self.orElse($expr)"

      override def apply(row: PageRow): Option[B] = {
        val selfValue = self(row)
        selfValue.orElse{ expr(row) }
      }
    }

    def get: NamedFunction1[PageRow, T] = self.andThen(
      NamedFunction1(_.get, s"get")
    )

    def ->[B](another: Expression[B]): Expression[(T, B)] = new Expression[(T, B)] {
      override val name: String = s"$self.->($another)"

      override def apply(row: PageRow): Option[(T, B)] = {
        if (self(row).isEmpty || another(row).isEmpty) None
        else Some(self(row).get -> another(row).get)
      }
    }

    def toSeqFunction: NamedFunction1[PageRow, Seq[T]] = self.andThen(_.toSeq)
    //  def defaultToHrefExpr = (self match {
    //    case expr: Expr[Unstructured] => expr.href
    //    case expr: Expr[Seq[Unstructured]] => expr.hrefs
    //    case _ => self
    //  }) > Symbol(Const.joinExprKey)

    //  def defaultToTextExpr = (this match {
    //    case expr: Expr[Unstructured] => expr.text
    //    case expr: Expr[Seq[Unstructured]] => expr.texts
    //    case _ => this
    //  }) as Symbol(Const.joinExprKey)
  }

  implicit def exprToExprView[Repr](expr: Repr)(implicit f: Repr => Expression[Any]): ExprView[Any] = f(expr)

  implicit class UnstructuredExprView(self: Expression[Unstructured]) extends Serializable {

    def uri: Expression[String] = self.andMap(_.uri, "uri")

    def findFirst(selector: String): FindFirstExpr = new FindFirstExpr(selector, self)

    def findAll(selector: String): FindAllExpr = new FindAllExpr(selector, self)

    def \\(selector: String) = findAll(selector)

    def child(selector: String): ChildExpr = new ChildExpr(selector, self)

    def children(selector: String): ChildrenExpr = new ChildrenExpr(selector, self)

    def \(selector: String) = children(selector)

    def text: Expression[String] = self.andFlatMap(_.text, "text")

    def code = self.andFlatMap(_.code, "code")

    def ownText: Expression[String] = self.andFlatMap(_.ownText, "ownText")

    def attr(attrKey: String, noEmpty: Boolean = true): Expression[String] = self.andFlatMap(_.attr(attrKey, noEmpty), s"attr($attrKey,$noEmpty)")

    def href = self.andFlatMap(_.href, s"href")

    def src = self.andFlatMap(_.src, s"src")

    def boilerPipe = self.andFlatMap(_.boilerPipe, "boilerPipe")
  }

  implicit class ElementsExprView(self: Expression[Elements[_]]) extends Serializable {

    def uris: Expression[Seq[String]] = self.andMap(_.uris, "uris")

    def texts: Expression[Seq[String]] = self.andMap(_.texts, "texts")

    def codes: Expression[Seq[String]] = self.andMap(_.codes, "text")

    def ownTexts: Expression[Seq[String]] = self.andMap(_.ownTexts, "ownTexts")

    def attrs(attrKey: String, noEmpty: Boolean = true): Expression[Seq[String]] = self.andMap(_.attrs(attrKey, noEmpty), s"attrs($attrKey,$noEmpty)")

    def hrefs = self.andMap(_.hrefs, s"hrefs")

    def srcs = self.andMap(_.srcs, s"srcs")

    def boilerPipes = self.andMap(_.boilerPipes, "text")
  }

  implicit class PageExprView(self: Expression[Page]) extends Serializable {

    def uid: Expression[PageUID] = self.andMap(_.uid, "uid")

    def contentType: Expression[String] = self.andMap(_.contentType, "contentType")

    def content: Expression[Seq[Byte]] = self.andMap(_.content.toSeq, "content")

    def timestamp: Expression[Date] = self.andMap(_.timestamp, "timestamp")

    def saved: Expression[ListSet[String]] = self.andMap(_.saved, "saved")

    def mimeType: Expression[String] = self.andMap(_.mimeType, "mimeType")

    def charSet: Expression[String] = self.andFlatMap(_.charset, "charSet")

    def exts: Expression[Seq[String]] = self.andMap(_.exts.toSeq, "extensions")

    def defaultExt: Expression[String] = self.andFlatMap(_.defaultExt, "defaultExt")
  }

  //  implicit class PageTraversableOnceExprView(self: Expression[TraversableOnce[Page]]) extends Serializable {
  //
  //    def timestamps: Expression[Seq[Date]] = self.andMap(_.toSeq.map(_.timestamp), "timestamps")
  //
  //    def saveds: Expression[Seq[ListSet[String]]] = self.andMap(_.toSeq.map(_.saved), "saveds")
  //  }

  implicit class IterableLikeExprView[T: ClassTag, Repr](self: Expression[IterableLike[T, Repr]]) extends Serializable {

    def head: Expression[T] = self.andFlatMap(_.headOption, "head")

    def last: Expression[T] = self.andFlatMap(_.lastOption, "last")

    def get(i: Int): Expression[T] = self.andFlatMap({
      iterable =>
        val realIdx = if (i >= 0) i
        else iterable.size - i

        if (realIdx>=iterable.size || realIdx<0) None
        else Some(iterable.toSeq.apply(realIdx))
    },
    s"get($i)")

    def size: Expression[Int] = self.andMap(_.size, "size")

    def isEmpty: Expression[Boolean] = self.andMap(_.isEmpty, "isEmpty")

    def nonEmpty: Expression[Boolean] = self.andMap(_.nonEmpty, "nonEmpty")

    def mkString(sep: String = ""): Expression[String] = self.andMap(_.mkString(sep), s"mkString($sep)")

    def mkString(start: String, sep: String, end: String): Expression[String] = self.andMap(_.mkString(start, sep, end), s"mkString($sep)")

    //TODO: Why IterableExprView.filter cannot be applied on ZippedExpr? is the scala compiler malfunctioning?
    def zipWithKeys(keys: Expression[Any]): ZippedExpr[Any, T] =
      new ZippedExpr[Any,T](keys.typed[IterableLike[_,_]], self)

    def zipWithValues(values: Expression[Any]): ZippedExpr[T, Any] =
      new ZippedExpr[T,Any](self, values.typed[IterableLike[_,_]])

    def groupBy[K](f: T => K): Expression[Map[K, Repr]] = self.andMap (
      v => v.groupBy(f),
      s"groupBy($f)"
    )

    def slice(from: Int = Int.MinValue, until: Int = Int.MaxValue): Expression[Repr] = self.andMap (
      v => v.slice(from, until),
      s"slice($from,$until)"
    )

    def filter(f: T => Boolean): Expression[Repr] = self.andMap(_.filter(f), s"filter($f)")

    def distinct: Expression[Seq[T]] = self.andMap(_.toSeq.distinct, "distinct")

    def distinctBy[K](f: T => K): Expression[Iterable[T]] = this.groupBy(f).andMap(
      v =>
        v.values.flatMap{
          case repr: Traversable[T] => repr.headOption
          case repr: T => Some(repr)
          case _ => None
        },
      s"distinctBy($f)"
    )

    //TODO: handle exception
    //  def only: Expr[T] =
    //    expr.andThen(NamedFunction1("only", _.map{
    //      seq =>
    //        assert(seq.size == 1)
    //        seq.head
    //    }))

    //TODO: these will cause unserializable exception, fix it!
    //    def map[B, That](f: T => B)(implicit bf: CanBuildFrom[Repr, B, That]): Expression[That] = self.andMap (
    //      v => {
    //        val vv: IterableLike[T, Repr] = v
    //        vv.map[B, That](f)(Serializable(bf))
    //      },
    //      s"map($f)"
    //    )
    //    def flatMap[B, That](f: T => GenTraversableOnce[B])(implicit bf: CanBuildFrom[Repr, B, That]): Expression[That] = self.andMap (
    //      v => v.flatMap[B, That](f)(Serializable(bf)),
    //      s"flatMap($f)"
    //    )

    def map[B](f: T => B): Expression[Seq[B]] = self.andMap (
      v => v.toSeq.map(f),
      s"map($f)"
    )

    def flatMap[B](f: T => GenTraversableOnce[B]): Expression[Seq[B]] = self.andMap (
      v => v.toSeq.flatMap(f),
      s"flatMap($f)"
    )

    def flatten: NamedFunction1[PageRow, Seq[T]] = self.andThen(_.toSeq.flatten)
  }

  implicit class StringExprView(self: Expression[String]) extends Serializable {

    def replaceAll(regex: String, replacement: String): Expression[String] =
      self.andMap(_.replaceAll(regex, replacement), s"replaceAll($regex,$replacement)")

    def trim: Expression[String] = self.andMap(_.trim, "trim")

    def +(another: Expression[Any]): Expression[String] = x"$self$another"
  }

  //--------------------------------------------------

  implicit def symbolToExpr(symbol: Symbol): GetExpr =
    new GetExpr(symbol.name)

  implicit def symbolToUnstructuredExprView(symbol: Symbol): UnstructuredExprView =
    new GetUnstructuredExpr(symbol.name)

  implicit def symbolToPageExprView(symbol: Symbol): PageExprView =
    new GetPageExpr(symbol.name)

  implicit def symbolToIterableLikeExprView(symbol: Symbol): IterableLikeExprView[Any, Seq[Any]] =
    new GetSeqExpr(symbol.name)

  implicit def stringToExpr(str: String): Expression[String] = {

    val delimiter = Const.keyDelimiter
    val regex = (delimiter+"\\{[^\\{\\}\r\n]*\\}").r

    if (regex.findFirstIn(str).isEmpty)
      new Literal[String](str)
    else
      new ReplaceKeyExpr(str)
  }

  implicit def stringRDDToItsView(rdd: RDD[String]): StringRDDView = new StringRDDView(rdd)

  implicit def dataFrameToItsView(rdd: DataFrame): DataFrameView = new DataFrameView(rdd)

  implicit class StrContextHelper(val strC: StringContext) extends Serializable {

    def x(fs: (PageRow => Option[Any])*) = new InterpolateExpr(strC.parts, fs)

    def CSS() = GetOnlyPageExpr.findAll(strC.s())
    def S() = CSS()

    def CSS_*() = GetAllPagesExpr.findAll(strC.s())
    def S_*() = CSS_*()

    def A() = 'A.findAll(strC.s())
  }
}