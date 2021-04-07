package pl.rebyrg.learningarrow

import arrow.continuations.Effect
import arrow.continuations.generic.DelimitedScope
import arrow.core.Either
import arrow.core.compose
import arrow.core.computations.EitherEffect
import arrow.core.left
import arrow.core.right
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import pl.rebyrg.learningarrow.reader.invoke
import java.util.stream.Stream
import kotlin.coroutines.RestrictsSuspension

//reader implementation
interface ReaderEitherEffect<D, E, A>: EitherEffect<E, A> {
    suspend fun ask(): D
    suspend fun <T, B> ((T) -> Either<E, B>).local(f: (D) -> T): (D) -> Either<E, B> = this.compose(f)
    suspend fun <B> ((D) -> Either<E, B>).bind(): B = this.invoke(ask()).bind()
}

fun <D, E, A> DelimitedScope<Either<E, A>>.effect(dependencies: D): ReaderEitherEffect<D, E, A> =
    object: ReaderEitherEffect<D, E, A> {
        override suspend fun ask(): D = dependencies
        override fun control(): DelimitedScope<Either<E, A>> = this@effect
    }

@RestrictsSuspension
interface RestrictedReaderEitherEffect<D, E, A> : ReaderEitherEffect<D, E, A>

fun <D, E, A> DelimitedScope<Either<E, A>>.restrictedEffect(dependencies: D): RestrictedReaderEitherEffect<D, E, A> =
    object: RestrictedReaderEitherEffect<D, E, A> {
        override suspend fun ask(): D = dependencies
        override fun control(): DelimitedScope<Either<E, A>> = this@restrictedEffect
    }

@Suppress("ClassName")
object reader {
    suspend inline operator fun <D, E, A> D.invoke(crossinline c: suspend ReaderEitherEffect<D, E, *>.() -> A): Either<E, A> =
        Effect.suspended(eff = { it.effect(this) }, f = c, just = { it.right() })

    suspend inline operator fun <D, E, A> invoke(crossinline c: suspend ReaderEitherEffect<D, E, *>.() -> A): suspend (D) -> Either<E, A> =
        { it(c) }

    inline fun <D, E, A> D.restricted(crossinline c: suspend RestrictedReaderEitherEffect<D, E, *>.() -> A): Either<E, A> =
        Effect.restricted(eff = { it.restrictedEffect(this) }, f = c, just = { it.right() })

    inline fun <D, E, A> eager(crossinline c: suspend RestrictedReaderEitherEffect<D, E, *>.() -> A): (D) -> Either<E, A> =
        { it.restricted(c) }
}

//example

//dependencies
interface Foo {
    fun value(): Int
}

interface Bar {
    fun value(): Int
}

interface FooBar {
    fun foo(): Foo
    fun bar(): Bar
}

//algebra
sealed class Error
object BadNumberFormat: Error()
object DivisionByZero: Error()

interface Algebra {

    //no dependency injection
    fun number(value: String): Either<Error, Int> =
        Either.catch { value.toInt() }.mapLeft { BadNumberFormat }

    //divide bar by passed value
    //inject Bar dependency and use it
    fun divide(value: Int): (Bar) -> Either<Error, Int> = { bar ->
        when (value) {
            0 -> DivisionByZero.left()
            else -> (bar.value() / value).right()
        }
    }

    //inject FooBar and use it
    fun add(value: Int): (FooBar) -> Either<Error, Int> = { fooBar ->
        (value + fooBar.foo().value() + fooBar.bar().value()).right()
    }

    //combine functions that returns Either<E, A> or (D) -> Either<E, A>
    fun combine(value: String): (FooBar) -> Either<Error, Int> =
        reader.eager {
            val a: Int = number(value).bind() //bind for plain Either and get it value
            val b: Int = add(a).bind() //bind for (D) -> Either and get it value
            val c: Int = divide(a).local { it.bar() }.bind() //map dependency and bind it
            val e: FooBar = ask() //grab dependencies
            a + b + c + e.foo().value() + e.bar().value() //compute it all and return result
        }
}

//test
class ReaderTest {

    object MyFooBar: FooBar {
        override fun foo(): Foo = object: Foo {
            override fun value(): Int = 3
        }
        override fun bar(): Bar = object: Bar {
            override fun value(): Int = 5
        }
    }

    object Computation: Algebra

    private fun data() = Stream.of(
        Arguments.of("not a number", BadNumberFormat.left()),
        Arguments.of("0", DivisionByZero.left()),
        Arguments.of("1", 23.right()),
        Arguments.of("2", 22.right()),
    )
    @ParameterizedTest
    @MethodSource("data")
    fun test(value: String, expected: Either<Throwable, Int>) {
        val calculate = Computation.combine(value)
        val result = calculate(MyFooBar) //inject dependencies
        Assertions.assertEquals(expected, result)
    }
}
