package net.corda.bn.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.bn.contracts.GroupContract
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
class DeleteGroupFlow(private val groupId: UniqueIdentifier, private val notary: Party? = null) : MembershipManagementFlow<SignedTransaction>() {

    @Suspendable
    override fun call(): SignedTransaction {
        // fetch group state with groupId linear ID
        val databaseService = serviceHub.cordaService(DatabaseService::class.java)
        val group = databaseService.getBusinessNetworkGroup(groupId)
                ?: throw BusinessNetworkGroupNotFoundException("Business Network group with $groupId linear ID doesn't exist")

        // check whether party is authorised to initiate flow
        val networkId = group.state.data.networkId
        authorise(networkId, databaseService) { it.canModifyRelationship() }

        // building transaction
        val builder = TransactionBuilder(notary ?: serviceHub.networkMapCache.notaryIdentities.first())
                .addInputState(group)
                .addCommand(GroupContract.Commands.Exit(), ourIdentity.owningKey)
        builder.verify(serviceHub)

        val observers = group.state.data.participants - ourIdentity
        val observerSessions = observers.map { initiateFlow(it) }
        return collectSignaturesAndFinaliseTransaction(builder, observerSessions, emptyList())
    }
}

@InitiatedBy(DeleteGroupFlow::class)
class DeleteGroupResponderFlow(private val session: FlowSession) : MembershipManagementFlow<Unit>() {

    @Suspendable
    override fun call() {
        signAndReceiveFinalisedTransaction(session) {
            if (it.value !is GroupContract.Commands.Exit) {
                throw FlowException("Only Exit command is allowed")
            }
        }
    }
}