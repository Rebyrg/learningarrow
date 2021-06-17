package pl.rebyrg.learningarrow

import arrow.core.Either
import arrow.core.computations.either
import arrow.core.left
import arrow.core.right
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

interface DependencyA {
    fun value(): Int
}

interface DependencyB {
    fun value(): Int
}

interface DependencyAB {
    fun a(): DependencyA
    fun b(): DependencyB
}

sealed class DIError {
    object BadNumberFormat : DIError()
    object DivisionByZero : DIError()
}

interface DIAlgebra {

    fun number(value: String): Either<DIError, Int> =
        Either.catch { value.toInt() }.mapLeft { DIError.BadNumberFormat }

    fun DependencyB.divide(value: Int): Either<DIError, Int> =
        when (value) {
            0 -> DIError.DivisionByZero.left()
            else -> (value() / value).right()
        }

    fun DependencyAB.add(value: Int): Either<DIError, Int> =
        (value + a().value() + b().value()).right()

    fun DependencyAB.compute(value: String): Either<DIError, Int> =
        either.eager {
            val a: Int = number(value).bind()
            val b: Int = add(a).bind()
            val c: Int = b().divide(a).bind()
            val e: DependencyAB = this@compute
            a + b + c + e.a().value() + e.b().value()
        }

    fun compute(value: String): DependencyAB.() -> Either<DIError, Int> = { this.compute(value) }
}

class DITest {

    object MyDependencyAB: DependencyAB {
        override fun a(): DependencyA = object: DependencyA {
            override fun value(): Int = 3
        }
        override fun b(): DependencyB = object: DependencyB {
            override fun value(): Int = 5
        }
    }

    object Computation: DIAlgebra

    private fun data() = Stream.of(
        Arguments.of("not a number", DIError.BadNumberFormat.left()),
        Arguments.of("0", DIError.DivisionByZero.left()),
        Arguments.of("1", 23.right()),
        Arguments.of("2", 22.right()),
    )
    @ParameterizedTest
    @MethodSource("data")
    fun test(value: String, expected: Either<Error, Int>) {
        val calculate = Computation.compute(value)
        val result = calculate(MyDependencyAB)
        Assertions.assertEquals(expected, result)
    }

}
