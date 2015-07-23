package org.tribbloid.spookystuff.pages

import scala.collection.{SeqLike, mutable}

/**
 * Created by peng on 18/07/15.
 */
object Elements {

  //  implicit def canBuildFrom[T <: Unstructured]: CanBuildFrom[Elements[Unstructured], T, Elements[T]] =
  //    new CanBuildFrom[Elements[Unstructured], T, Elements[T]] {
  //
  //      override def apply(from: Elements[Unstructured]): mutable.Builder[T, Elements[T]] = newBuilder[T]
  //
  //      override def apply(): mutable.Builder[T, Elements[T]] = newBuilder[T]
  //    }

  def newBuilder[T <: Unstructured]: mutable.Builder[T, Elements[T]] = new mutable.Builder[T, Elements[T]] {

    val buffer = new mutable.ArrayBuffer[T]()

    override def +=(elem: T): this.type = {
      buffer += elem
      this
    }

    override def result(): Elements[T] = new Elements(buffer.toList)

    override def clear(): Unit = buffer.clear()
  }
}

class Elements[+T <: Unstructured](val self: List[T]) extends Unstructured
with Seq[T]
with SeqLike[T, Elements[T]] {

  def uris: Seq[String] = self.map(_.uri)

  def codes: Seq[String] = self.flatMap(_.code)

  def attrs(attr: String, noEmpty: Boolean = true): Seq[String] = self.flatMap(_.attr(attr, noEmpty))

  final def hrefs(abs: Boolean) = attrs("abs:href")

  final def srcs = attrs("abs:src")

  def texts: Seq[String] = self.flatMap(_.text)

  def ownTexts: Seq[String] = self.flatMap(_.ownText)

  def boilerPipes: Seq[String] = self.flatMap(_.boilerPipe)

  override def uri: String = uris.headOption.orNull

  override def text: Option[String] = texts.headOption

  override def code: Option[String] = codes.headOption

  override def children(selector: String) = new Elements(self.flatMap(_.children(selector)))

  override def childrenWithSiblings(start: String, range: Range) = new Elements(self.flatMap(_.childrenWithSiblings(start, range)))

  override def ownText: Option[String] = ownTexts.headOption

  override def boilerPipe: Option[String] = boilerPipes.headOption

  override def attr(attr: String, noEmpty: Boolean): Option[String] = attrs(attr, noEmpty).headOption

  override def iterator: Iterator[T] = self.iterator

  override def length: Int = self.length

  override def apply(idx: Int): T = self.apply(idx)

  override protected[this] def newBuilder = Elements.newBuilder[T]
}