package net.corda.bn.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.bn.contracts.GroupContract
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.FlowException
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder

@InitiatingFlow
@StartableByRPC
class ModifyGroupFlow(
        private val groupId: UniqueIdentifier,
        private val name: String? = null,
        private val participants: Set<UniqueIdentifier>? = null,
        private val notary: Party? = null
) : MembershipManagementFlow<SignedTransaction>() {

    @Suspendable
    override fun call(): SignedTransaction {
        // validate flow arguments
        if (name == null && participants == null) {
            throw FlowException("One of the name or participants arguments must be specified")
        }

        // fetch group state with groupId linear ID
        val databaseService = serviceHub.cordaService(DatabaseService::class.java)
        val group = databaseService.getBusinessNetworkGroup(groupId)
                ?: throw BusinessNetworkGroupNotFoundException("Business Network group with $groupId linear ID doesn't exist")

        // check whether party is authorised to initiate flow
        val networkId = group.state.data.networkId
        authorise(networkId, databaseService) { it.canModifyRelationship() }

        // get all additional participant identities from provided membership ids
        val participantsIdentities = participants?.map {
            val membership = databaseService.getMembership(it)
                    ?: throw MembershipNotFoundException("Cannot find membership with $it linear ID")

            membership.state.data.identity.cordaIdentity
        }

        // building transaction
        val outputGroup = group.state.data.let { group ->
            group.copy(
                    name = name ?: group.name,
                    participants = participantsIdentities ?: group.participants,
                    modified = serviceHub.clock.instant()
            )
        }
        val builder = TransactionBuilder(notary ?: serviceHub.networkMapCache.notaryIdentities.first())
                .addInputState(group)
                .addOutputState(outputGroup)
                .addCommand(GroupContract.Commands.Modify(), ourIdentity.owningKey)
        builder.verify(serviceHub)

        // send transaction to all participants of modified group
        val observers = outputGroup.participants - ourIdentity
        val observerSessions = observers.map { initiateFlow(it) }
        return collectSignaturesAndFinaliseTransaction(builder, observerSessions, emptyList())
    }
}

@StartableByRPC
class ModifyGroupShellFlow(private val groupId: UniqueIdentifier, private val participants: Set<Party>) : FlowLogic<SignedTransaction>() {

    @Suspendable
    override fun call(): SignedTransaction {
        val databaseService = serviceHub.cordaService(DatabaseService::class.java)
        val group = databaseService.getBusinessNetworkGroup(groupId)
                ?: throw BusinessNetworkGroupNotFoundException("Business Network group with $groupId linear ID doesn't exist")

        val networkId = group.state.data.networkId
        val membershipIds = participants.map {
            databaseService.getMembership(networkId, it)?.state?.data?.linearId
                    ?: throw MembershipNotFoundException("Cannot find membership for $it")
        }

        return subFlow(ModifyGroupFlow(groupId, null, membershipIds.toSet(), null))
    }
}

@InitiatedBy(ModifyGroupFlow::class)
class ModifyGroupResponderFlow(private val session: FlowSession) : MembershipManagementFlow<Unit>() {

    @Suspendable
    override fun call() {
        signAndReceiveFinalisedTransaction(session) {
            if (it.value !is GroupContract.Commands.Modify) {
                throw FlowException("Only Modify command is allowed")
            }
        }
    }
}