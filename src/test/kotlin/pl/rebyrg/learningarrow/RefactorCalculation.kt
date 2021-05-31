package pl.rebyrg.learningarrow

import arrow.core.*
import arrow.core.computations.either

typealias EventId = String
typealias Premium = Double
data class Event(val id: EventId, val sum: Double, val premium: Premium)
typealias BenefitId = String
data class Benefit(val id: String, val name: String, val value: Double)
data class EventSelection(val id: EventId, val sum: Double)

sealed class UserSelection(open val industry: String)
enum class CalculationMode { STANDARD, EXPERT }
data class DynamicUserSelection(override val industry: String, val mode: CalculationMode, val eventsSelection: List<EventSelection>): UserSelection(industry)
data class FixedUserSelection(override val industry: String, val fixedId: FixedId): UserSelection(industry)

interface RootAggregate<I> {
    val id: I
}

typealias FixedId = Long
data class Fixed(override val id: FixedId, val events: List<Event>): RootAggregate<FixedId>
data class EventConstraint(val id: EventId, val min: Double, val max: Double)
data class CalculationConstant(val id: EventId, val factor: Double)
data class BenefitTemplate(val id: BenefitId, val name: String, val events: Set<EventId>)

typealias CalculationNumber = Long
data class Draft(override val id: CalculationNumber, val selection: UserSelection): RootAggregate<CalculationNumber>
data class Final(override val id: CalculationNumber, val draftId: CalculationNumber, val events: List<Event>): RootAggregate<CalculationNumber>

typealias VersionId = Int
sealed class VersionRange {
    abstract fun match(versionId: VersionId): Boolean
    object All: VersionRange() {
        override fun match(versionId: VersionId): Boolean = true
    }
    data class From(val from: VersionId): VersionRange() {
        override fun match(versionId: VersionId): Boolean = versionId >= from
    }
    data class To(val to: VersionId): VersionRange() {
        override fun match(versionId: VersionId): Boolean = versionId <= to
    }
    data class Range(val from: VersionId, val to: VersionId): VersionRange() {
        override fun match(versionId: VersionId): Boolean = versionId in from .. to
    }
}

//1. repozytoria to interfejsy, ktore w adapterach sa podpinane do repozytoriow JPA
interface Repository<I, V> {
    fun findById(id: I): V?
}
//2. definicje to obiekty i wartosci ktore teraz mamy rozproszone po roznych val, object, companion object
interface Definition<D> {
    fun get(versionId: VersionId): D?
}
//3. Domyslne wartosc ? //TODO
//4. Generatory identyfikatorow
interface IdGenerator<out I> {
    fun next(): I
}
//5. Feigny //todo
//6. Wersjonowanie
interface Versioned<out T> {
    fun get(versionId: VersionId): T?
}

data class VersionedValues<T>(private val values: Map<VersionRange, T>): Versioned<T> {
    override fun get(versionId: VersionId): T? =
        values.entries.firstOrNull { it.key.match(versionId) }?.value
}
interface CalculationNumberGenerator: IdGenerator<CalculationNumber>
interface DraftRepository: Repository<CalculationNumber, Draft>
interface FinalRepository: Repository<CalculationNumber, Final>
interface FixedRepository: Repository<FixedId, Fixed>
interface EventsDefinition: Definition<Map<CalculationMode, List<EventConstraint>>>
interface CalculationConstantsDefinition: Definition<List<CalculationConstant>>
interface BenefitsDefinition: Definition<List<BenefitTemplate>>

//interfejsy uzywajace zaleznosci na potrzeby DI
interface VersionedDependency {
    fun version(): VersionId
}
interface CalculationNumberGeneratorDependency {
    val calculationNumberGenerator: CalculationNumberGenerator
}
interface DraftRepositoryDependency {
    val draftRepository: DraftRepository
}
interface FinalRepositoryDependency {
    val finalRepository: FinalRepository
}
interface FixedRepositoryDependency {
    val fixedRepository: FixedRepository
}
interface EventsDefinitionDependency {
    val eventsDefinition: EventsDefinition
}
interface CalculationConstantsDefinitionDependency {
    val calculationConstantsDefinition: CalculationConstantsDefinition
}
interface BenefitsDefinitionDependency {
    val benefitsDefinition: BenefitsDefinition
}

//algebry
//dla uproszczenia wszystkie bledy to stringi, ale docelowo musza byc rozne typy per algebra, oraz wzajemne zagniezdzanie
typealias AlgebraError = String
typealias AlgebraEither<T> = Either<AlgebraError, T>

//pomocnicze metody operujace na repozytoriach, definicjach itp.
typealias BaseAlgebraDependency = VersionedDependency

interface BaseAlgebra {
    fun <E> mandatory(getter: () -> E?, onError: (() -> AlgebraError) = { "expected value" }): AlgebraEither<E> =
        getter()?.right() ?: onError().left()

    fun <T, D: Definition<T>> BaseAlgebraDependency.mandatoryDefinition(definition: D): AlgebraEither<T> =
        mandatory({ definition.get(version()) }, { "nod definition available" } )
}

//compute draft
interface ComputeDraftAlgebraDependency: BaseAlgebraDependency, DraftRepositoryDependency

interface ComputeDraftAlgebra: BaseAlgebra {

    fun ComputeDraftAlgebraDependency.edit(id: CalculationNumber, modification: (UserSelection) -> AlgebraEither<UserSelection>): AlgebraEither<Draft> =
        either.eager {
            val draft = find(id).bind()
            draft.copy(selection = modification(draft.selection).bind())
        }

    fun ComputeDraftAlgebraDependency.find(id: CalculationNumber): AlgebraEither<Draft> =
        mandatory( { this@find.draftRepository.findById(id) }, { "calculation $id not found"} )

}

//edit: industry
interface EditIndustryAlgebraDependency: ComputeDynamicAlgebraDependency

interface EditIndustryAlgebra: ComputeDynamicAlgebra {

    fun EditIndustryAlgebraDependency.changeIndustry(id: CalculationNumber, industry: String): AlgebraEither<Draft> =
        edit(id) { selection -> changeIndustry(selection, industry) }

    //logike nie piszemy w encjach tylko w algebrach
    private fun EditIndustryAlgebraDependency.changeIndustry(selection: UserSelection, industry: String): AlgebraEither<UserSelection> =
        when (selection) {
            is FixedUserSelection -> changeFixedIndustry(selection, industry)
            is DynamicUserSelection -> changeDynamicIndustry(selection, industry)
        }

    private fun EditIndustryAlgebraDependency.changeFixedIndustry(selection: FixedUserSelection, industry: String): AlgebraEither<UserSelection> =
        selection.copy(industry = industry).right()

    private fun EditIndustryAlgebraDependency.changeDynamicIndustry(selection: DynamicUserSelection, industry: String): AlgebraEither<UserSelection> =
        compensate(selection.copy(industry = industry))

}

//compute events definition
interface ComputeDynamicAlgebraDependency: ComputeDraftAlgebraDependency, EventsDefinitionDependency, CalculationConstantsDefinitionDependency

//wspolne metody dla dynamic uzywane przez poszczegolne operacje
interface ComputeDynamicAlgebra: ComputeDraftAlgebra {
    fun ComputeDynamicAlgebraDependency.findDynamic(id: CalculationNumber): AlgebraEither<Draft> =
        find(id).flatMap { draft ->
            when (draft.selection) {
                is FixedUserSelection -> "can not process fixed draft id $id".left()
                is DynamicUserSelection -> draft.right()
            }
        }

    fun ComputeDynamicAlgebraDependency.editDynamic(id: CalculationNumber, modification: (DynamicUserSelection) -> AlgebraEither<DynamicUserSelection>): AlgebraEither<Draft> =
        findDynamic(id).flatMap { draft ->
            modification(draft.selection as DynamicUserSelection).map { selection ->
                draft.copy(selection = selection)
            }
        }

    fun ComputeDynamicAlgebraDependency.eventsConstraints(selection: DynamicUserSelection): AlgebraEither<List<EventConstraint>> =
        mandatoryDefinition(eventsDefinition).flatMap { definition ->
            definition[selection.mode]?.right()
                ?: "nod events constraint for mode ${selection.mode}".left()
        }

    fun ComputeDynamicAlgebraDependency.generate(selection: DynamicUserSelection): AlgebraEither<List<Event>> =
        either.eager {
            val constraints = eventsConstraints(selection).bind().associateBy { it.id }
            selection.eventsSelection.traverseEither { (id, sum) ->
                val (_, min, max) = constraints[id]!!
                when (sum) {
                    in min .. max -> Event(id, sum, 0.0).right()
                    else -> "sum for event $id not in range [$min .. $max]".left()
                }
            }.bind()
        }

    fun ComputeDynamicAlgebraDependency.calculate(selection: DynamicUserSelection): AlgebraEither<List<Event>> =
        either.eager {
            val generated = generate(selection).bind()
            val constants = mandatoryDefinition(calculationConstantsDefinition).bind().associateBy( { it.id }, { it.factor })
            generated.map { event ->
                val factorEither = constants[event.id]?.right() ?: "no calculation factor for event ${event.id}".left()
                val factor = factorEither.bind()
                event.copy(premium = event.sum * factor)
            }
        }

    fun ComputeDynamicAlgebraDependency.compensate(selection: DynamicUserSelection): AlgebraEither<DynamicUserSelection> =
        if (generate(selection).isLeft()) {
            either.eager {
                val eventsDefinition = eventsConstraints(selection).bind().associateBy { it.id }
                val adjustedEventsSelection = selection.eventsSelection
                    .filter { eventsDefinition.keys.contains(it.id) }
                    .map { eventSelection ->
                        val (_, min, max) = eventsDefinition[eventSelection.id]!!
                        when (eventSelection.sum) {
                            in min..max -> eventSelection
                            else -> EventSelection(eventSelection.id, min)
                        }
                    }
                selection.copy(eventsSelection = adjustedEventsSelection)
            }
        } else {
            selection.right()
        }
}

//create dynamic draft
interface CreateDynamicAlgebraDependencies: ComputeDynamicAlgebraDependency, CalculationNumberGeneratorDependency

interface CreateDynamicAlgebra: ComputeDynamicAlgebra {

    fun CreateDynamicAlgebraDependencies.createDynamic(needs: Set<EventId>): AlgebraEither<Draft> =
        either.eager {
            val definition = mandatoryDefinition(eventsDefinition).bind()
            val mode = CalculationMode.STANDARD
            val eventsEither = definition[mode]?.right() ?: "no events definition for mode $mode".left()
            val events = eventsEither.bind()
            val availableIds = events.map { it.id }
            if (availableIds.containsAll(needs)) {
                val industry = "default industry"
                val eventsSelection = events.filter { needs.contains(it.id) }.map { EventSelection(it.id, it.min) }
                val id = this@createDynamic.calculationNumberGenerator.next()
                Draft(id, DynamicUserSelection(industry, mode, eventsSelection)).right()
            } else {
                "needs not matched to product".left()
            }.bind()
        }
}

//edit: mode
typealias EditModeAlgebraDependency = ComputeDynamicAlgebraDependency

interface EditModeAlgebra: ComputeDynamicAlgebra {

    fun EditModeAlgebraDependency.changeMode(id: CalculationNumber, mode: CalculationMode): AlgebraEither<Draft> =
        editDynamic(id) { compensate(it.copy(mode = mode)) }
}

//compute fixed
interface ComputeFixedAlgebraDependency: ComputeDraftAlgebraDependency, FixedRepositoryDependency

interface ComputeFixedAlgebra: ComputeDraftAlgebra {
    fun ComputeFixedAlgebraDependency.findFixedDefinition(id: FixedId): AlgebraEither<Fixed> =
        mandatory( { this@findFixedDefinition.fixedRepository.findById(id) }, { "fixed $id not found"} )

    fun ComputeFixedAlgebraDependency.fixedEvents(selection: FixedUserSelection): AlgebraEither<List<Event>> =
        findFixedDefinition(selection.fixedId).map { it.events }
}
//events
interface DraftEventsAlgebraDependency: ComputeDraftAlgebraDependency, ComputeDynamicAlgebraDependency, ComputeFixedAlgebraDependency
interface DraftEventsAlgebra: ComputeDraftAlgebra, ComputeDynamicAlgebra, ComputeFixedAlgebra {

    fun DraftEventsAlgebraDependency.events(id: CalculationNumber): AlgebraEither<List<Event>> =
        find(id).flatMap { draft -> events(draft.selection) }

    fun DraftEventsAlgebraDependency.events(selection: UserSelection): AlgebraEither<List<Event>> =
        when (selection) {
            is DynamicUserSelection -> calculate(selection)
            is FixedUserSelection -> fixedEvents(selection)
        }

}

//benefits
interface BenefitsAlgebraDependency: BenefitsDefinitionDependency, BaseAlgebraDependency

interface BenefitsAlgebra: BaseAlgebra {

    fun BenefitsAlgebraDependency.benefits(events: List<Event>): AlgebraEither<List<Benefit>> =
        either.eager {
            val benefitDefinition = mandatoryDefinition(benefitsDefinition).bind()
            benefitDefinition.map { (id, name, eventIds) ->
                Benefit(id, name, events.filter { eventIds.contains(it.id) }.map { it.sum }.reduce(Double::plus))
            }
        }
}

interface DraftBenefitsAlgebraDependency: DraftEventsAlgebraDependency, BenefitsAlgebraDependency
interface DraftBenefitsAlgebra: DraftEventsAlgebra, BenefitsAlgebra {
    fun DraftBenefitsAlgebraDependency.benefits(id: CalculationNumber): AlgebraEither<List<Benefit>> =
        events(id).flatMap { benefits(it) }
}

//create fixed
interface CreateFixedAlgebraDependency: FixedRepositoryDependency, CalculationNumberGeneratorDependency

interface CreateFixedAlgebra: BaseAlgebra {
    fun CreateFixedAlgebraDependency.createFixed(fixedId: FixedId): AlgebraEither<Draft> =
        either.eager {
            mandatory( { fixedRepository.findById(fixedId) }, { "fixed definition for id $fixedId does not exists "} ).bind()
            val industry = "default industry"
            val calculationId = this@createFixed.calculationNumberGenerator.next()
            Draft(calculationId, FixedUserSelection(industry, fixedId))
        }
}

////commit
interface CommitAlgebraDependency: DraftEventsAlgebraDependency, CalculationNumberGeneratorDependency
interface CommitAlgebra: DraftEventsAlgebra, CalculationNumberGenerator {
    fun CommitAlgebraDependency.commit(id: CalculationNumber): AlgebraEither<Final> =
        either.eager {
            val draft = find(id).bind()
            val events = events(draft.selection).bind()
            val finalId = calculationNumberGenerator.next()
            Final(finalId, draft.id, events)
        }
}

///////////////////////////////////
interface Store {
    fun <I, T: RootAggregate<I>> save(entity: T): T
}

interface StoreDependency {
    val store: Store
}

interface Command<in D, out R> {
    fun D.execute(): AlgebraEither<R>
    fun execute(): D.() -> AlgebraEither<R> = { this.execute() }
}

//przykladowe commandy
interface  CreateFixedDraftDependency: StoreDependency, CreateFixedAlgebraDependency
data class CreateFixedDraftCommand(val fixedId: FixedId): Command<CreateFixedDraftDependency, CalculationNumber>, CreateFixedAlgebra {
    override fun CreateFixedDraftDependency.execute(): AlgebraEither<CalculationNumber> =
        createFixed(fixedId).map { store.save(it).id }
}

interface EditIndustryCommandDependency: StoreDependency, EditIndustryAlgebraDependency
data class EditIndustryCommand(val id: CalculationNumber, val industry: String): Command<EditIndustryCommandDependency, Unit>, EditIndustryAlgebra {
    override fun EditIndustryCommandDependency.execute(): AlgebraEither<Unit> =
        changeIndustry(id, industry).map { store.save(it) }
}

interface ApplicationDependencies: CreateFixedDraftDependency, EditIndustryCommandDependency //todo pozostale zaleznosci per command

/*@Service*/
data class Application(/*@Autowired*/val dependency: ApplicationDependencies) {

    private fun <R> execute(command: Command<ApplicationDependencies, R>): AlgebraEither<R> =
        command.execute()(dependency)

    fun createFixed(fixedId: FixedId): AlgebraEither<CalculationNumber> = execute(CreateFixedDraftCommand(fixedId))
    fun editIndustry(id: CalculationNumber, industry: String): AlgebraEither<Unit> = execute(EditIndustryCommand(id, industry))
}
