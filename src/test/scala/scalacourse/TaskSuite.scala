package scalacourse

import cats.implicits._
import cats.laws.discipline.MonadErrorTests
import scala.concurrent._

class TaskSuite extends BaseSuite {

  checkAllAsync("MonadError[Task]") { implicit ec =>
    MonadErrorTests[Task, Throwable].monadError[Int, Int, Int]
  }

  test("Task.apply suspends side effects") {
    import ExecutionContext.Implicits.global

    var x = 0
    val task = Task { x += 1; x }

    valueOf(task) shouldBe Right(1)
    valueOf(task) shouldBe Right(2)
  }

  test("Task.suspend suspends side effects") {
    import ExecutionContext.Implicits.global

    var x = 0
    val task = Task.suspend { x += 1; Task.pure(x) }

    valueOf(task) shouldBe Right(1)
    valueOf(task) shouldBe Right(2)
  }

  test("Task.parMap2 is consistent with flatMap") {
    import ExecutionContext.Implicits.global

    check { (ta: Task[Int], tb: Task[Int], f: (Int, Int) => Long) =>
      val r1 = valueOf(Task.parMap2(ta, tb)(f))
      val r2 = valueOf(ta.flatMap(a => tb.map(b => f(a, b))))
      r1 == r2
    }
  }

  test("Task.sequence") {
    import ExecutionContext.Implicits.global

    check { list: List[Int] =>
      val sum = valueOf(Task.sequence(list.map(Task(_))).map(_.sum))
      sum == Right(list.sum)
    }
  }

  test("Task.parallel") {
    import ExecutionContext.Implicits.global

    check { list: List[Int] =>
      val sum = valueOf(Task.parallel(list.map(Task(_))).map(_.sum))
      sum == Right(list.sum)
    }
  }
}
