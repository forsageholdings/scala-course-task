package scalacourse

import cats.{MonadError, StackSafeMonad}
import scala.concurrent.{ExecutionContext, ExecutionException, Future, Promise}
import scala.util.{Failure, Success}


final class Task[A] private (runFn: ExecutionContext => Future[A]) { self =>
  /**
    * Executes the task.
    */
  def unsafeRunToFuture(implicit ec: ExecutionContext): Future[A] =
    runFn(ec)

  /**
    * Returns a new task that transforms the value of the
    * source via the given function.
    *
    * This is the `Functor.map`, see:
    * [[https://typelevel.org/cats/typeclasses/functor.html]]
    */
  def map[B](f: A => B): Task[B] =
    new Task[B](implicit ec => {
      self.unsafeRunToFuture(ec).map(f)
    })

  /**
    * Returns a new task that transforms the value of the
    * source via the given function and then flattens the result.
    *
    * This is the `Monad.flatMap`, see:
    * [[https://typelevel.org/cats/typeclasses/monad.html]]
    */
  def flatMap[B](f: A => Task[B]): Task[B] =
    new Task[B](implicit ec => {
      val p = Promise[B]()
      self.unsafeRunToFuture(ec).onComplete {
        case Success(value) =>
          p.completeWith(f(value).unsafeRunToFuture(ec))
        case Failure(exception) =>
          p.failure(exception)
      }
      p.future
    })

  /**
    * Exposes thrown errors by the implementation.
    *
    * Example:
    * {{{
    *   val task: Task[Int] = Task { throw new RuntimeException("Boom!") }
    *
    *   val task2: Task[Either[Throwable, Int]] = task.attempt
    * }}}
    *
    * In the above example the first task has a hidden (unforseen) exception thrown
    * by the implementation, whereas `task2` exposes it via an `Either` result.
    */
  def attempt: Task[Either[Throwable, A]] =
    new Task(implicit ec => {
      val p = Promise[Either[Throwable, A]]()
      self.unsafeRunToFuture(ec).onComplete {
        case Success(value) =>
          p.success(Right(value))
        case Failure(e: ExecutionException) if e.getCause != null =>
          // Future boxes some exceptions in ExecutionException, so as an API
          // quirk it's best if we unboxed it ourselves ;-)
          p.success(Left(e.getCause))
        case Failure(e) =>
          p.success(Left(e))
      }
      p.future
    })
}

object Task {
  /**
    * Callback type to use in async computations.
    */
  type Callback[A] = Either[Throwable, A] => Unit

  /**
    * Builds a `Task` that's already evaluated to the given value.
    *
    * The equivalent of `Future.successful`.
    */
  def pure[A](a: A): Task[A] =
    new Task[A](_ => Future.successful(a))

  /**
    * Builds a `Task` that's already evaluated to the given exception.
    *
    * The equivalent of `Future.raiseError`.
    */
  def raiseError[A](e: Throwable): Task[A] =
    new Task[A](_ => Future.failed(e))

  /**
    * Builds a task that evaluates the given expression on another thread,
    * suspending any side effects in the context of the returned `Task` value.
    */
  def apply[A](f: => A): Task[A] =
    new Task[A](ec => Future(f)(ec))

  /**
    * Suspends the side effects associated with the created `Task` value.
    *
    * This can be used for recursions, for example:
    * {{{
    *   def fib(n: Int, a: Long = 0, b: Long = 1): Task[Long] =
    *     Task.suspend {
    *       if (n > 0)
    *         fib(n - 1, b, a + b)
    *       else
    *         Task.pure(a)
    *     }
    * }}}
    */
  def suspend[A](f: => Task[A]): Task[A] =
    apply(f).flatMap(x => x)

  /**
    * Wraps any asynchronous API in a `Task`.
    *
    * This is the pure equivalent of Scala's `Promise`.
    *
    * {{{
    *   import java.util.function.BiFunction
    *   import org.asynchttpclient.Dsl._
    *   import org.asynchttpclient._
    *
    *   type HTTPError = String
    *
    *   def requestGET(client: AsyncHttpClient, url: String): Task[Either[HTTPError, String]] =
    *     Task.async { callback =>
    *       val javaFuture = client.prepareGet(url).execute().toCompletableFuture
    *
    *       javaFuture.handle[Unit](
    *         new BiFunction[Response, Throwable, Unit] {
    *           def apply(resp: Response, error: Throwable): Unit = {
    *             if (error != null) {
    *               callback(Left(error))
    *             } else if (resp.getStatusCode >= 400) {
    *               callback(Right(Left("HTTP Error: " + resp.getStatusCode)))
    *             } else {
    *               callback(Right(Right(resp.getResponseBody)))
    *             }
    *           }
    *         })
    *     }
    * }}}
    */
  def async[A](cb: Callback[A] => Unit): Task[A] =
    async0((cb0, _) => cb(cb0))

  /**
    * Like [[async]], except that this version also gives the
    * user a `scala.concurrent.ExecutionContext` that can be used for
    * dealing with multi-threaded execution or with Scala's `Future`.
    *
    * {{{
    *   Task.async0 { (callback, context) =>
    *     implicit val ec = context
    *
    *     Future {
    *       callback(Right("Hello from another thread!"))
    *     }
    *   }
    * }}}
    */
  def async0[A](start: (Callback[A], ExecutionContext) => Unit): Task[A] =
    new Task[A](ec => {
      val p = Promise[A]()
      val cb: Callback[A] =
        result => {
          p.complete(result.toTry)
        }

      start(cb, ec)
      p.future
    })

  /**
    * Describes a task that on evaluation will execute the given tasks in parallel.
    * On completion of both tasks the given function `f` will execute to produce a final result.
    *
    * {{{
    *   val task1 = Task { "Hello" }
    *   val task2 = Task { "world!" }
    *
    *   Task.parMap2(task1, task2) { (prefix, suffix) =>
    *     prefix + ", " + suffix
    *   }
    * }}}
    */
  def parMap2[A, B, C](first: Task[A], second: Task[B])(f: (A, B) => C): Task[C] =
    new Task(implicit ec => {
      val f1 = first.unsafeRunToFuture(ec)
      val f2 = second.unsafeRunToFuture(ec)

      for (a <- f1; b <- f2) yield f(a, b)
    })

  /**
    * Executes the given task list sequentially, one after another.
    */
  def sequence[A](list: List[Task[A]]): Task[List[A]] = {
    val taskList = list.foldLeft(Task.pure(List.empty[A])) { (acc, task) =>
      acc.flatMap { list =>
        task.map { elem =>
          elem :: list
        }
      }
    }
    // List will be in reverse compared with the order of execution, so we'll have
    // to reverse it for producing the final result
    taskList.map(_.reverse)
  }

  /**
    * Executes the given task list in parallel, returning a list of results that's
    * collected in the order of evaluation.
    */
  def parallel[A](list: List[Task[A]]): Task[List[A]] = {
    val taskList = list.foldLeft(Task.pure(List.empty[A])) { (acc, task) =>
      Task.parMap2(acc, task)((list, e) => e :: list)
    }
    // List will be in reverse compared with the order of execution, so we'll have
    // to reverse it for producing the final result
    taskList.map(_.reverse)
  }

  /**
    * Type class instances for [[Task]].
    *
    * See: [[https://typelevel.org/cats/]]
    */
  implicit val instances: MonadError[Task, Throwable] =
    new MonadError[Task, Throwable] with StackSafeMonad[Task] {
      def flatMap[A, B](fa: Task[A])(f: A => Task[B]): Task[B] =
        fa.flatMap(f)

      def raiseError[A](e: Throwable): Task[A] =
        Task.raiseError(e)

      override def attempt[A](fa: Task[A]): Task[Either[Throwable, A]] =
        fa.attempt

      def handleErrorWith[A](fa: Task[A])(f: Throwable => Task[A]): Task[A] =
        fa.attempt.flatMap {
          case Right(r) => Task.pure(r)
          case Left(e) => f(e)
        }

      def pure[A](x: A): Task[A] =
        Task.pure(x)
    }
}
