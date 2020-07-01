package net.corda.bn.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.bn.contracts.GroupContract
import net.corda.bn.states.GroupState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.FlowException
import net.corda.core.flows.FlowSession
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder

@InitiatingFlow
@StartableByRPC
class CreateGroupFlow(
        private val networkId: String,
        private val groupName: String? = null,
        private val groupId: UniqueIdentifier = UniqueIdentifier(),
        private val additionalParticipants: Set<UniqueIdentifier> = emptySet(),
        private val notary: Party? = null
) : MembershipManagementFlow<SignedTransaction>() {

    @Suspendable
    override fun call(): SignedTransaction {
        // check whether party is authorised to initiate flow
        val databaseService = serviceHub.cordaService(DatabaseService::class.java)
        authorise(networkId, databaseService) { it.canModifyRelationship() }

        // check whether group with groupId already exists
        if (databaseService.businessNetworkGroupExists(groupId)) {
            throw DuplicateBusinessNetworkGroupException("Business Network group with $groupId ID already exists")
        }

        // get all additional participant identities from provided membership ids
        val additionalParticipantsIdentities = additionalParticipants.map {
            val membership = databaseService.getMembership(it)
                    ?: throw MembershipNotFoundException("Cannot find membership with $it linear ID")

            membership.state.data.identity.cordaIdentity
        }.toSet()

        // building transaction
        val group = GroupState(networkId = networkId, name = groupName, linearId = groupId, participants = (additionalParticipantsIdentities + ourIdentity).toList())
        val builder = TransactionBuilder(notary ?: serviceHub.networkMapCache.notaryIdentities.first())
                .addOutputState(group)
                .addCommand(GroupContract.Commands.Create(), ourIdentity.owningKey)
        builder.verify(serviceHub)

        // send transaction to all additional participants of created group
        val observers = additionalParticipantsIdentities - ourIdentity
        val observerSessions = observers.map { initiateFlow(it) }
        return collectSignaturesAndFinaliseTransaction(builder, observerSessions, emptyList())
    }
}

@InitiatedBy(CreateGroupFlow::class)
class CreateGroupResponderFlow(private val session: FlowSession) : MembershipManagementFlow<Unit>() {

    @Suspendable
    override fun call() {
        signAndReceiveFinalisedTransaction(session) {
            if (it.value !is GroupContract.Commands.Create) {
                throw FlowException("Only Create command is allowed")
            }
        }
    }
}

class DuplicateBusinessNetworkGroupException(message: String) : FlowException(message)