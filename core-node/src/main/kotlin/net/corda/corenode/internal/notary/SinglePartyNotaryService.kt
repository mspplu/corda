package net.corda.corenode.internal.notary

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.concurrent.CordaFuture
import net.corda.core.contracts.StateRef
import net.corda.core.contracts.TimeWindow
import net.corda.core.crypto.Crypto
import net.corda.core.crypto.SecureHash
import net.corda.core.crypto.SignableData
import net.corda.core.crypto.SignatureMetadata
import net.corda.core.crypto.TransactionSignature
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.NotarisationRequestSignature
import net.corda.core.identity.Party
import net.corda.corenode.internal.notary.UniquenessProvider.Result
import net.corda.core.serialization.CordaSerializable
import net.corda.core.utilities.contextLogger
import net.corda.corenode.internal.FlowAsyncOperation
import net.corda.corenode.internal.executeAsync
import org.slf4j.Logger
import java.time.Duration

/** Base implementation for a notary service operated by a singe party. */
abstract class SinglePartyNotaryService : NotaryService() {
    companion object {
        private val staticLog = contextLogger()
    }

    protected open val log: Logger get() = staticLog

    /** Handles input state uniqueness checks. */
    protected abstract val uniquenessProvider: UniquenessProvider

    /** Attempts to commit the specified transaction [txId]. */
    @Suspendable
    open fun commitInputStates(
            inputs: List<StateRef>,
            txId: SecureHash,
            caller: Party,
            requestSignature: NotarisationRequestSignature,
            timeWindow: TimeWindow?,
            references: List<StateRef>
    ) {
        // TODO: Log the request here. Benchmarking shows that logging is expensive and we might get better performance
        // when we concurrently log requests here as part of the flows, instead of logging sequentially in the
        // `UniquenessProvider`.

        val callingFlow = FlowLogic.currentTopLevel
                ?: throw IllegalStateException("This method should be invoked in a flow context.")

        val result = callingFlow.executeAsync(
                CommitOperation(
                        this,
                        inputs,
                        txId,
                        caller,
                        requestSignature,
                        timeWindow,
                        references
                )
        )

        if (result is UniquenessProvider.Result.Failure) {
            throw NotaryInternalException(result.error)
        }
    }

    /**
     * Estimate the wait time to be notarised taking into account the new request size.
     *
     * @param numStates The number of states we're about to request be notarised.
     */
    fun getEstimatedWaitTime(numStates: Int): Duration = uniquenessProvider.getEta(numStates)

    /**
     * Required for the flow to be able to suspend until the commit is complete.
     * This object will be included in the flow checkpoint.
     */
    @CordaSerializable
    class CommitOperation(
            val service: SinglePartyNotaryService,
            val inputs: List<StateRef>,
            val txId: SecureHash,
            val caller: Party,
            val requestSignature: NotarisationRequestSignature,
            val timeWindow: TimeWindow?,
            val references: List<StateRef>
    ) : FlowAsyncOperation<Result> {

        override fun execute(deduplicationId: String): CordaFuture<Result> {
            return service.uniquenessProvider.commit(inputs, txId, caller, requestSignature, timeWindow, references)
        }
    }

    /** Sign a single transaction. */
    fun signTransaction(txId: SecureHash): TransactionSignature {
        val signableData = SignableData(txId, SignatureMetadata(services.myInfo.platformVersion, Crypto.findSignatureScheme(notaryIdentityKey).schemeNumberID))
        return services.keyManagementService.sign(signableData, notaryIdentityKey)
    }
}