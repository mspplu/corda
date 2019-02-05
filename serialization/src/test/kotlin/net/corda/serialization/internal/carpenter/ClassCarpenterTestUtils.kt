package net.corda.serialization.internal.carpenter

import com.google.common.reflect.TypeToken
import net.corda.core.serialization.ClassWhitelist
import net.corda.core.serialization.SerializedBytes
import net.corda.serialization.internal.amqp.*
import net.corda.serialization.internal.amqp.testutils.deserializeAndReturnEnvelope
import net.corda.serialization.internal.amqp.testutils.serialize
import net.corda.serialization.internal.model.*
import net.corda.core.internal.reflection.TypeIdentifier
import net.corda.serialization.internal.amqp.schema.SerializationSchemas
import net.corda.serialization.internal.amqp.factories.SerializerFactoryBuilder
import net.corda.serialization.internal.amqp.schema.AMQPRemoteTypeModel
import net.corda.serialization.internal.amqp.schema.Envelope
import net.corda.serialization.internal.amqp.utils.asClass
import org.junit.Assert.assertTrue

/**
 * Custom implementation of a [SerializerFactory] where we need to give it a class carpenter
 * rather than have it create its own
 */
private fun serializerFactoryExternalCarpenter(classCarpenter: ClassCarpenter)
    = SerializerFactoryBuilder.build(classCarpenter.whitelist, classCarpenter)

open class AmqpCarpenterBase(whitelist: ClassWhitelist) {
    var cc = ClassCarpenterImpl(whitelist = whitelist)
    var factory = serializerFactoryExternalCarpenter(cc)

    protected val remoteTypeModel = AMQPRemoteTypeModel()
    protected val typeLoader = ClassCarpentingTypeLoader(SchemaBuildingRemoteTypeCarpenter(cc), cc.classloader)

    protected inline fun <reified T: Any> T.roundTrip(): ObjectAndEnvelope<T> =
        DeserializationInput(factory).deserializeAndReturnEnvelope(serialise(this))

    protected val Envelope.typeInformation: Map<TypeDescriptor, RemoteTypeInformation> get() =
        remoteTypeModel.interpret(SerializationSchemas(schema, transformsSchema))

    protected inline fun <reified T: Any> Envelope.typeInformationFor(): RemoteTypeInformation {
        val interpreted = typeInformation
        val type = object : TypeToken<T>() {}.type
        return interpreted.values.find { it.typeIdentifier == TypeIdentifier.forGenericType(type) }
                as RemoteTypeInformation
    }

    protected inline fun <reified T: Any> Envelope.getMangled(): RemoteTypeInformation =
            typeInformationFor<T>().mangle<T>()

    protected fun <T: Any> serialise(obj: T): SerializedBytes<T> = SerializationOutput(factory).serialize(obj)

    protected inline fun <reified T: Any>  RemoteTypeInformation.mangle(): RemoteTypeInformation {
        val from = TypeIdentifier.forGenericType(object : TypeToken<T>() {}.type)
        return rename(from, from.mangle())
    }

    protected fun TypeIdentifier.mangle(): TypeIdentifier = when(this) {
        is TypeIdentifier.Unparameterised -> copy(name = name + "_carpenter")
        is TypeIdentifier.Parameterised -> copy(name = name + "_carpenter")
        is TypeIdentifier.Erased -> copy(name = name + "_carpenter")
        is TypeIdentifier.ArrayOf -> copy(componentType = componentType.mangle())
        else -> this
    }

    protected fun TypeIdentifier.rename(from: TypeIdentifier, to: TypeIdentifier): TypeIdentifier = when(this) {
        from -> to.rename(from, to)
        is TypeIdentifier.Parameterised -> copy(parameters = parameters.map { it.rename(from, to) })
        is TypeIdentifier.ArrayOf -> copy(componentType = componentType.rename(from, to))
        else -> this
    }

    protected fun RemoteTypeInformation.rename(from: TypeIdentifier, to: TypeIdentifier): RemoteTypeInformation = when(this) {
        is RemoteTypeInformation.Composable -> copy(
                typeIdentifier = typeIdentifier.rename(from, to),
                properties = properties.mapValues { (_, property) -> property.copy(type = property.type.rename(from, to)) },
                interfaces = interfaces.map { it.rename(from, to) },
                typeParameters = typeParameters.map { it.rename(from, to) })
        is RemoteTypeInformation.Unparameterised -> copy(typeIdentifier = typeIdentifier.rename(from, to))
        is RemoteTypeInformation.Parameterised -> copy(
                typeIdentifier = typeIdentifier.rename(from, to),
                typeParameters = typeParameters.map { it.rename(from, to) })
        is RemoteTypeInformation.AnInterface -> copy(
                typeIdentifier = typeIdentifier.rename(from, to),
                properties = properties.mapValues { (_, property) -> property.copy(type = property.type.rename(from, to)) },
                interfaces = interfaces.map { it.rename(from, to) },
                typeParameters = typeParameters.map { it.rename(from, to) })
        is RemoteTypeInformation.AnArray -> copy(componentType = componentType.rename(from, to))
        is RemoteTypeInformation.AnEnum -> copy(
                typeIdentifier = typeIdentifier.rename(from, to))
        else -> this
    }

    protected fun RemoteTypeInformation.load(): Class<*> =
            typeLoader.load(listOf(this))[typeIdentifier]!!.asClass()

    protected fun assertCanLoadAll(vararg types: RemoteTypeInformation) {
        assertTrue(typeLoader.load(types.asList()).keys.containsAll(types.map { it.typeIdentifier }))
    }

    protected fun Class<*>.new(vararg constructorParams: Any?) =
            constructors[0].newInstance(*constructorParams)!!

    protected fun Any.get(propertyName: String): Any =
            this::class.java.getMethod("get${propertyName.capitalize()}").invoke(this)
}