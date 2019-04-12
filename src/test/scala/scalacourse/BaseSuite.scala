package scalacourse

import cats.Eq
import org.scalacheck.Arbitrary.{arbitrary => getArbitrary}
import org.scalacheck.{Arbitrary, Cogen, Gen}
import org.scalatest.prop.Checkers
import org.scalatest.{FunSuite, Matchers}
import org.typelevel.discipline.Laws
import org.typelevel.discipline.scalatest.Discipline
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

trait BaseSuite extends FunSuite with Matchers with Checkers with Discipline {

  def checkAllAsync(name: String)(f: ExecutionContext => Laws#RuleSet): Unit = {
    val ruleSet = f(ExecutionContext.global)

    for ((id, prop) ← ruleSet.all.properties)
      test(name + "." + id) {
        check(prop)
      }
  }

  implicit def arbitraryForTask[A: Arbitrary: Cogen]: Arbitrary[Task[A]] =
    Arbitrary(Gen.delay(genTask[A]))

  implicit def cogenForTask[A](implicit cga: Cogen[A], ec: ExecutionContext): Cogen[Task[A]] =
    Cogen { (seed, io) =>
      Await.result(io.attempt.unsafeRunToFuture, 3.seconds) match {
        case Right(r) => cga.perturb(seed, r)
        case _ => seed
      }
    }

  implicit def eqforTask[A](implicit A: Eq[Either[Throwable, A]], ec: ExecutionContext): Eq[Task[A]] =
    (x: Task[A], y: Task[A]) => {
      A.eqv(valueOf(x), valueOf(y))
    }

  implicit val eqForThrowable: Eq[Throwable] =
    (_: Throwable, _: Throwable) => true

  def genPure[A: Arbitrary]: Gen[Task[A]] =
    getArbitrary[A].map(Task.pure)

  def genApply[A: Arbitrary]: Gen[Task[A]] =
    getArbitrary[A].map(Task.apply(_))

  def genFail[A]: Gen[Task[A]] =
    getArbitrary[Throwable].map(Task.raiseError)

  def genAsync[A: Arbitrary]: Gen[Task[A]] =
    getArbitrary[(Either[Throwable, A] => Unit) => Unit].map(Task.async)

  def genFlatMap[A: Arbitrary: Cogen]: Gen[Task[A]] =
    for {
      ioa <- getArbitrary[Task[A]]
      f <- getArbitrary[A => Task[A]]
    } yield ioa.flatMap(f)

  def getMapOne[A: Arbitrary: Cogen]: Gen[Task[A]] =
    for {
      ioa <- getArbitrary[Task[A]]
      f <- getArbitrary[A => A]
    } yield ioa.map(f)

  def genTask[A: Arbitrary: Cogen]: Gen[Task[A]] =
    Gen.frequency(
      1 -> genPure[A],
      1 -> genApply[A],
      1 -> genFail[A],
      1 -> genAsync[A],
      1 -> getMapOne[A],
      1 -> genFlatMap[A]
    )

  def valueOf[A](task: Task[A])(implicit ec: ExecutionContext): Either[Throwable, A] =
    Await.result(task.attempt.unsafeRunToFuture, 5.seconds)
}
