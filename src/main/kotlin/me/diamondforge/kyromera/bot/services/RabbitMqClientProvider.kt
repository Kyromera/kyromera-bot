package me.diamondforge.kyromera.bot.services

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.DefaultConsumer
import com.rabbitmq.client.Envelope
import io.github.freya022.botcommands.api.core.service.annotations.BService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import me.diamondforge.kyromera.bot.configuration.Config
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

private val logger = KotlinLogging.logger {}

@BService
class RabbitMqClientProvider(
    config: Config,
) {
    private val connectionFactory = ConnectionFactory().apply {
        host = config.rabbitMqConfig.host
        port = config.rabbitMqConfig.port
        username = config.rabbitMqConfig.user
        password = config.rabbitMqConfig.password
        isAutomaticRecoveryEnabled = true
    }

    private val connection: Connection = connectionFactory.newConnection()
    private val channel: Channel = connection.createChannel()
    private val json = Json { ignoreUnknownKeys = true }
    private val coroutineScope = CoroutineScope(Dispatchers.Default)
    private val subscriptions = ConcurrentHashMap<String, String>() 
    private val executor = Executors.newCachedThreadPool()

    init {
        Runtime.getRuntime().addShutdownHook(
            Thread {
                runCatching {
                    subscriptions.forEach { (queueName, _) ->
                        try {
                            unsubscribe(queueName)
                            logger.info { "Closed RabbitMQ subscription for queue: $queueName" }
                        } catch (e: Exception) {
                            logger.warn(e) { "Error closing RabbitMQ subscription for queue: $queueName" }
                        }
                    }

                    channel.close()
                    connection.close()
                }.onSuccess {
                    logger.info { "RabbitMQ channel and connection closed" }
                }.onFailure {
                    logger.warn(it) { "Error during RabbitMQ shutdown" }
                }
            },
        )

        logger.info { "Initialized RabbitMQ connection at ${connectionFactory.host}:${connectionFactory.port}" }
    }

    /**
     * Gets a value from RabbitMQ. Since RabbitMQ is not a key-value store like Redis,
     * this is implemented using a temporary queue and RPC pattern.
     *
     * @param key The key to get
     * @return The value associated with the key, or null if not found
     */
    suspend fun get(key: String): String? =
        runCatching {
            val replyQueueName = channel.queueDeclare().queue
            val correlationId = java.util.UUID.randomUUID().toString()

            val future = CompletableFuture<String?>()

            val ctag = channel.basicConsume(
                replyQueueName,
                true,
                object : DefaultConsumer(channel) {
                    override fun handleDelivery(
                        consumerTag: String,
                        envelope: Envelope,
                        properties: AMQP.BasicProperties,
                        body: ByteArray
                    ) {
                        if (properties.correlationId == correlationId) {
                            future.complete(String(body, StandardCharsets.UTF_8))
                            channel.basicCancel(consumerTag)
                        }
                    }
                }
            )

            val props = AMQP.BasicProperties.Builder()
                .correlationId(correlationId)
                .replyTo(replyQueueName)
                .build()

            channel.basicPublish(
                "kyromera.kv",
                "get.$key",
                props,
                key.toByteArray(StandardCharsets.UTF_8)
            )

            val result = future.await()
            channel.basicCancel(ctag)
            result
        }.onFailure { logger.error(it) { "Failed to get key '$key'" } }
            .getOrNull()

    /**
     * Sets a value in RabbitMQ. Since RabbitMQ is not a key-value store like Redis,
     * this is implemented using a message to a specific routing key.
     *
     * @param key The key to set
     * @param value The value to set
     * @return True if the operation was successful, false otherwise
     */
    suspend fun set(
        key: String,
        value: String,
    ): Boolean =
        runCatching {
            channel.exchangeDeclare("kyromera.kv", "topic", true)
            channel.basicPublish(
                "kyromera.kv",
                "set.$key",
                null,
                value.toByteArray(StandardCharsets.UTF_8)
            )
            true
        }.onFailure { logger.error(it) { "Failed to set key '$key'" } }
            .getOrDefault(false)

    /**
     * Subscribes to a RabbitMQ queue and executes the provided callback function when a message is received.
     *
     * @param queueName The queue to subscribe to
     * @param callback The function to execute when a message is received
     * @return True if the subscription was successful, false otherwise
     */
    fun subscribe(
        queueName: String,
        callback: (String) -> Unit,
    ): Boolean {
        return runCatching {
            if (subscriptions.containsKey(queueName)) {
                logger.warn { "Already subscribed to queue: $queueName" }
                return true
            }

            // Declare a durable exchange
            channel.exchangeDeclare("kyromera.pubsub", "fanout", true)

            // Declare a queue that will survive broker restarts
            channel.queueDeclare(queueName, true, false, false, null)

            // Bind the queue to the exchange
            channel.queueBind(queueName, "kyromera.pubsub", queueName)

            val consumerTag = channel.basicConsume(
                queueName,
                true, // auto-ack
                object : DefaultConsumer(channel) {
                    override fun handleDelivery(
                        consumerTag: String,
                        envelope: Envelope,
                        properties: AMQP.BasicProperties,
                        body: ByteArray
                    ) {
                        val message = String(body, StandardCharsets.UTF_8)
                        logger.trace { "Received message on queue '$queueName': $message" }
                        coroutineScope.launch {
                            try {
                                callback(message)
                            } catch (e: Exception) {
                                logger.error(e) { "Error in callback for message on queue '$queueName'" }
                            }
                        }
                    }
                }
            )

            subscriptions[queueName] = consumerTag
            logger.info { "Subscribed to queue: $queueName" }
            true
        }.onFailure {
            logger.error(it) { "Failed to subscribe to queue: $queueName" }
        }.getOrDefault(false)
    }

    /**
     * Subscribes to a RabbitMQ queue and executes the provided callback function when a request is received.
     * The callback function receives the request message and a function to send a response.
     *
     * @param queueName The queue to subscribe to
     * @param callback The function to execute when a request is received
     * @return True if the subscription was successful, false otherwise
     */
    fun subscribeWithResponse(
        queueName: String,
        callback: (String, (String) -> Unit) -> Unit,
    ): Boolean {
        return runCatching {
            if (subscriptions.containsKey(queueName)) {
                logger.warn { "Already subscribed to queue: $queueName" }
                return true
            }

            // Declare a durable exchange for requests
            channel.exchangeDeclare("kyromera.rpc", "direct", true)

            // Declare a queue that will survive broker restarts
            channel.queueDeclare(queueName, true, false, false, null)

            // Bind the queue to the exchange
            channel.queueBind(queueName, "kyromera.rpc", queueName)

            val consumerTag = channel.basicConsume(
                queueName,
                false, // manual ack
                object : DefaultConsumer(channel) {
                    override fun handleDelivery(
                        consumerTag: String,
                        envelope: Envelope,
                        properties: AMQP.BasicProperties,
                        body: ByteArray
                    ) {
                        val message = String(body, StandardCharsets.UTF_8)
                        logger.trace { "Received request on queue '$queueName': $message" }

                        // Create a response function that sends a reply to the replyTo queue
                        val respond: (String) -> Unit = { response ->
                            properties.replyTo?.let { replyTo ->
                                val replyProps = AMQP.BasicProperties.Builder()
                                    .correlationId(properties.correlationId)
                                    .build()

                                channel.basicPublish(
                                    "", // default exchange
                                    replyTo,
                                    replyProps,
                                    response.toByteArray(StandardCharsets.UTF_8)
                                )
                                logger.trace { "Sent response to $replyTo: $response" }
                            }
                        }

                        coroutineScope.launch {
                            try {
                                callback(message, respond)
                                // Acknowledge the message after processing
                                channel.basicAck(envelope.deliveryTag, false)
                            } catch (e: Exception) {
                                logger.error(e) { "Error in callback for request on queue '$queueName'" }
                                // Reject the message and requeue it
                                channel.basicNack(envelope.deliveryTag, false, true)
                            }
                        }
                    }
                }
            )

            subscriptions[queueName] = consumerTag
            logger.info { "Subscribed to queue for requests: $queueName" }
            true
        }.onFailure {
            logger.error(it) { "Failed to subscribe to queue for requests: $queueName" }
        }.getOrDefault(false)
    }

    /**
     * Subscribes to a RabbitMQ queue and executes the provided callback function when a typed request is received.
     * The callback function receives the deserialized request object and a function to send a typed response.
     *
     * @param queueName The queue to subscribe to
     * @param requestSerializer The serializer to use for the request
     * @param responseSerializer The serializer to use for the response
     * @param callback The function to execute when a request is received
     * @return True if the subscription was successful, false otherwise
     */
    fun <REQ, RESP> subscribeWithResponseTyped(
        queueName: String,
        requestSerializer: KSerializer<REQ>,
        responseSerializer: KSerializer<RESP>,
        callback: (REQ, (RESP) -> Unit) -> Unit,
    ): Boolean {
        return runCatching {
            if (subscriptions.containsKey(queueName)) {
                logger.warn { "Already subscribed to queue: $queueName" }
                return true
            }

            // Declare a durable exchange for requests
            channel.exchangeDeclare("kyromera.rpc", "direct", true)

            // Declare a queue that will survive broker restarts
            channel.queueDeclare(queueName, true, false, false, null)

            // Bind the queue to the exchange
            channel.queueBind(queueName, "kyromera.rpc", queueName)

            val consumerTag = channel.basicConsume(
                queueName,
                false, // manual ack
                object : DefaultConsumer(channel) {
                    override fun handleDelivery(
                        consumerTag: String,
                        envelope: Envelope,
                        properties: AMQP.BasicProperties,
                        body: ByteArray
                    ) {
                        val message = String(body, StandardCharsets.UTF_8)
                        logger.trace { "Received typed request on queue '$queueName': $message" }

                        // Create a response function that sends a reply to the replyTo queue
                        val respond: (RESP) -> Unit = { response ->
                            properties.replyTo?.let { replyTo ->
                                val replyProps = AMQP.BasicProperties.Builder()
                                    .correlationId(properties.correlationId)
                                    .build()

                                val responseJson = json.encodeToString(responseSerializer, response)
                                channel.basicPublish(
                                    "", // default exchange
                                    replyTo,
                                    replyProps,
                                    responseJson.toByteArray(StandardCharsets.UTF_8)
                                )
                                logger.trace { "Sent typed response to $replyTo" }
                            }
                        }

                        coroutineScope.launch {
                            try {
                                val request = json.decodeFromString(requestSerializer, message)
                                callback(request, respond)
                                // Acknowledge the message after processing
                                channel.basicAck(envelope.deliveryTag, false)
                            } catch (e: Exception) {
                                logger.error(e) { "Error in callback for typed request on queue '$queueName'" }
                                // Reject the message and requeue it
                                channel.basicNack(envelope.deliveryTag, false, true)
                            }
                        }
                    }
                }
            )

            subscriptions[queueName] = consumerTag
            logger.info { "Subscribed to queue for typed requests: $queueName" }
            true
        }.onFailure {
            logger.error(it) { "Failed to subscribe to queue for typed requests: $queueName" }
        }.getOrDefault(false)
    }

    /**
     * Unsubscribes from a RabbitMQ queue.
     *
     * @param queueName The queue to unsubscribe from
     * @return True if the unsubscription was successful, false otherwise
     */
    fun unsubscribe(queueName: String): Boolean {
        return runCatching {
            val consumerTag = subscriptions[queueName] ?: run {
                logger.warn { "Not subscribed to queue: $queueName" }
                return true
            }

            channel.basicCancel(consumerTag)
            subscriptions.remove(queueName)

            logger.info { "Unsubscribed from queue: $queueName" }
            true
        }.onFailure {
            logger.error(it) { "Failed to unsubscribe from queue: $queueName" }
        }.getOrDefault(false)
    }

    /**
     * Publishes a message to a RabbitMQ exchange.
     *
     * @param routingKey The routing key to publish to
     * @param message The message to publish
     * @return True if the message was published successfully, false otherwise
     */
    suspend fun publish(
        routingKey: String,
        message: String,
    ): Boolean =
        runCatching {
            channel.exchangeDeclare("kyromera.pubsub", "fanout", true)
            channel.basicPublish(
                "kyromera.pubsub",
                routingKey,
                null,
                message.toByteArray(StandardCharsets.UTF_8)
            )
            true
        }.onFailure {
            logger.error(it) { "Failed to publish message to routing key: $routingKey" }
        }.getOrDefault(false)

    /**
     * Sends a request and waits for a response.
     *
     * This method implements a request-response pattern using RabbitMQ.
     * It creates a temporary queue for the response, sends the request with a correlation ID,
     * and waits for a response with the same correlation ID.
     *
     * @param exchange The exchange to publish to
     * @param routingKey The routing key to publish to
     * @param message The message to publish
     * @param timeout The timeout in milliseconds (default: 30000)
     * @return The response message, or null if no response was received within the timeout
     */
    suspend fun requestResponse(
        exchange: String,
        routingKey: String,
        message: String,
        timeout: Long = 30000
    ): String? =
        runCatching {
            val replyQueueName = channel.queueDeclare().queue
            val correlationId = java.util.UUID.randomUUID().toString()

            val future = CompletableFuture<String?>()

            val ctag = channel.basicConsume(
                replyQueueName,
                true,
                object : DefaultConsumer(channel) {
                    override fun handleDelivery(
                        consumerTag: String,
                        envelope: Envelope,
                        properties: AMQP.BasicProperties,
                        body: ByteArray
                    ) {
                        if (properties.correlationId == correlationId) {
                            future.complete(String(body, StandardCharsets.UTF_8))
                            channel.basicCancel(consumerTag)
                        }
                    }
                }
            )

            val props = AMQP.BasicProperties.Builder()
                .correlationId(correlationId)
                .replyTo(replyQueueName)
                .build()

            channel.basicPublish(
                exchange,
                routingKey,
                props,
                message.toByteArray(StandardCharsets.UTF_8)
            )

            val result = future.await()
            channel.basicCancel(ctag)
            result
        }.onFailure { logger.error(it) { "Failed to get response for request to $exchange/$routingKey" } }
            .getOrNull()

    /**
     * Convenience method to send a request to a queue and wait for a response.
     * 
     * This method uses the "kyromera.rpc" exchange and the provided queue name as the routing key.
     * 
     * @param queueName The queue to send the request to
     * @param message The message to send
     * @param timeout The timeout in milliseconds (default: 30000)
     * @return The response message, or null if no response was received within the timeout
     */
    suspend fun request(
        queueName: String,
        message: String,
        timeout: Long = 30000
    ): String? = requestResponse("kyromera.rpc", queueName, message, timeout)

    /**
     * Sends a typed request and waits for a typed response.
     *
     * This method serializes the request object, sends it, and deserializes the response.
     *
     * @param exchange The exchange to publish to
     * @param routingKey The routing key to publish to
     * @param request The request object to send
     * @param requestSerializer The serializer to use for the request
     * @param responseSerializer The serializer to use for the response
     * @param timeout The timeout in milliseconds (default: 30000)
     * @return The deserialized response object, or null if no response was received within the timeout
     */
    suspend fun <REQ, RESP> requestResponseTyped(
        exchange: String,
        routingKey: String,
        request: REQ,
        requestSerializer: KSerializer<REQ>,
        responseSerializer: KSerializer<RESP>,
        timeout: Long = 30000
    ): RESP? =
        runCatching {
            val requestJson = json.encodeToString(requestSerializer, request)
            val responseJson = requestResponse(exchange, routingKey, requestJson, timeout)
            responseJson?.let { json.decodeFromString(responseSerializer, it) }
        }.onFailure { logger.error(it) { "Failed to get typed response for request to $exchange/$routingKey" } }
            .getOrNull()

    /**
     * Convenience method to send a typed request to a queue and wait for a typed response.
     * 
     * This method uses the "kyromera.rpc" exchange and the provided queue name as the routing key.
     * 
     * @param queueName The queue to send the request to
     * @param request The request object to send
     * @param requestSerializer The serializer to use for the request
     * @param responseSerializer The serializer to use for the response
     * @param timeout The timeout in milliseconds (default: 30000)
     * @return The deserialized response object, or null if no response was received within the timeout
     */
    suspend fun <REQ, RESP> requestTyped(
        queueName: String,
        request: REQ,
        requestSerializer: KSerializer<REQ>,
        responseSerializer: KSerializer<RESP>,
        timeout: Long = 30000
    ): RESP? = requestResponseTyped(
        "kyromera.rpc", 
        queueName, 
        request, 
        requestSerializer, 
        responseSerializer, 
        timeout
    )

    /**
     * Gets a typed value from RabbitMQ.
     *
     * @param key The key to get
     * @param serializer The serializer to use for deserialization
     * @return The deserialized value, or null if not found
     */
    suspend fun <T> getTyped(
        key: String,
        serializer: KSerializer<T>,
    ): T? =
        runCatching {
            get(key)?.let { json.decodeFromString(serializer, it) }
        }.onFailure {
            logger.error(it) { "Failed to deserialize key '$key'" }
        }.getOrNull()

    /**
     * Sets a typed value in RabbitMQ.
     *
     * @param key The key to set
     * @param value The value to set
     * @param serializer The serializer to use for serialization
     * @return True if the operation was successful, false otherwise
     */
    suspend fun <T> setTyped(
        key: String,
        value: T,
        serializer: KSerializer<T>,
    ): Boolean =
        runCatching {
            val encoded = json.encodeToString(serializer, value)
            set(key, encoded)
        }.onFailure {
            logger.error(it) { "Failed to serialize and set key '$key'" }
        }.getOrDefault(false)

    /**
     * Deletes a key from RabbitMQ.
     *
     * @param key The key to delete
     * @return True if the operation was successful, false otherwise
     */
    suspend fun delete(key: String): Boolean =
        runCatching {
            channel.exchangeDeclare("kyromera.kv", "topic", true)
            channel.basicPublish(
                "kyromera.kv",
                "delete.$key",
                null,
                key.toByteArray(StandardCharsets.UTF_8)
            )
            true
        }.onFailure { logger.error(it) { "Failed to delete key '$key'" } }
            .getOrDefault(false)
}
