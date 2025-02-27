package tests.pc

import java.lang
import scala.meta.internal.jdk.CollectionConverters._
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean
import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.pc.CancelToken
import tests.BaseCompletionSuite
import scala.meta.internal.pc.InterruptException
import munit.Location
import java.net.URI
import scala.meta.internal.metals.EmptyCancelToken

class CancelCompletionSuite extends BaseCompletionSuite {

  /**
   * A cancel token that cancels asynchronously on first `checkCancelled` call.
   */
  class AlwaysCancelToken extends CancelToken {
    val cancel = new CompletableFuture[lang.Boolean]()
    var isCancelled = new AtomicBoolean(false)
    override def onCancel(): CompletionStage[lang.Boolean] = cancel
    override def checkCanceled(): Unit = {
      if (isCancelled.compareAndSet(false, true)) {
        cancel.complete(true)
      } else {
        Thread.sleep(10)
      }
    }
  }

  def checkCancelled(
      name: String,
      query: String,
      expected: String,
      compat: Map[String, String]
  )(implicit loc: Location): Unit = {
    test(name) {
      val (code, offset) = params(query)
      val token = new AlwaysCancelToken
      try {
        presentationCompiler
          .complete(
            CompilerOffsetParams(
              URI.create("file:///A.scala"),
              code,
              offset,
              token
            )
          )
          .get()
        fail("Expected completion request to be interrupted")
      } catch {
        case InterruptException() =>
          assert(token.isCancelled.get())
      }

      // assert that regular completion works as expected.
      val completion = presentationCompiler
        .complete(
          CompilerOffsetParams(
            URI.create("file:///A.scala"),
            code,
            offset,
            EmptyCancelToken
          )
        )
        .get()
      val expectedCompat =
        getExpected(expected, compat, scalaVersion)
      val obtained = completion.getItems.asScala
        .map(_.getLabel)
        .mkString("\n")
      assertNoDiff(obtained, expectedCompat)
    }
  }

  checkCancelled(
    "basic",
    """
      |object A {
      |  val x = asser@@
      |}
    """.stripMargin,
    """|assert(assertion: Boolean): Unit
       |assert(assertion: Boolean, message: => Any): Unit
       |""".stripMargin,
    compat = Map(
      "0.23" ->
        """|assert(assertion: => Boolean @InlineParam): Unit
           |assertFail(message: => Any): Nothing
           |""".stripMargin,
      "0.24" ->
        """|assert(assertion: Boolean @InlineParam): Unit
           |assertFail(message: => Any): Nothing
           |""".stripMargin
    )
  )

}
