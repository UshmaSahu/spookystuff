package com.tribbloids.spookystuff.example

import com.tribbloids.spookystuff.actions.{Submit, TextInput, Visit}
import com.tribbloids.spookystuff.dsl._
import com.tribbloids.spookystuff.expressions.Expression

/**
 * Created by peng on 22/07/15.
 */
object Common {

  def googleSearch(col: Expression[Any]) = Visit("http://www.google.com/") +>
    TextInput("input[name=\"q\"]",col) +>
    Submit("input[name=\"btnG\"]")
}
