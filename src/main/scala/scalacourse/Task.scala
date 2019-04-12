package scalacourse

import cats.{MonadError, StackSafeMonad}

import scala.concurrent.{ExecutionContext, Future}

final class Task[A](runFn: ExecutionContext => Future[A]) {
  /**
    * Executes the task.
    */
  def unsafeRunToFuture(implicit ec: ExecutionContext): Future[A] =
    runFn(ec)

  def map[B](f: A => B): Task[B] = ???

  def flatMap[B](f: A => Task[B]): Task[B] = ???

  def attempt: Task[Either[Throwable, A]] = ???
}

object Task {
  /**
    * Callback type to use in async computations.
    */
  type Callback[A] = Either[Throwable, A] => Unit

  def pure[A](a: A): Task[A] =
    new Task[A](_ => Future.successful(a))

  def raiseError[A](e: Throwable): Task[A] =
    new Task[A](_ => Future.failed(e))

  def apply[A](f: => A): Task[A] = ???

  def suspend[A](ta: => Task[A]): Task[A] = ???

  def async0[A](cb: (Callback[A], ExecutionContext) => Unit): Task[A] = ???

  def async[A](cb: Callback[A] => Unit): Task[A] =
    async0((cb0, _) => cb(cb0))

  def parMap2[A, B, C](ta: Task[A], tb: Task[B])(f: (A, B) => C): Task[C] = ???

  def sequence[A](list: List[Task[A]]): Task[List[A]] = ???

  def parallel[A](list: List[Task[A]]): Task[List[A]] = ???

  /**
    * Type class instances for [[Task]].
    */
  implicit val instances: MonadError[Task, Throwable] =
    new MonadError[Task, Throwable] with StackSafeMonad[Task] {
      def flatMap[A, B](fa: Task[A])(f: A => Task[B]): Task[B] =
        fa.flatMap(f)

      def raiseError[A](e: Throwable): Task[A] =
        Task.raiseError(e)

      def handleErrorWith[A](fa: Task[A])(f: Throwable => Task[A]): Task[A] =
        fa.attempt.flatMap {
          case Right(r) => Task.pure(r)
          case Left(e) => f(e)
        }

      def pure[A](x: A): Task[A] =
        Task.pure(x)
    }
}
