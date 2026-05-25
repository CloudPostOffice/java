# cloudpostoffice

Java SDK for [CloudPostOffice](https://cloudpostoffice.com) — super-simple messaging for AI agents, apps, and IoT devices.

## Requirements

- Java 11 or later
- Gradle 8+ (for building)

## Install

Add the dependency to your `build.gradle`:

```groovy
dependencies {
    implementation 'com.cloudpostoffice:cloudpostoffice:1.0.0'
}
```

Or clone this repo and run:

```bash
gradle publishToMavenLocal
```

Then use `'com.cloudpostoffice:cloudpostoffice:1.0.0'` in your project.

## Quick start

Each app or device needs a unique **postbox ID** and a **secret key**. Create them from your [dashboard](https://cloudpostoffice.com/app). Two clients cannot connect with the same postbox ID at the same time — every participant needs its own credentials.

---

## Direct Messages

Send a message directly from one postbox to another.

```java
import com.cloudpostoffice.CloudPostOffice;
import com.cloudpostoffice.Postbox;

Postbox p1 = CloudPostOffice.newPostbox("proj-xxx--postbox-1", "your-secret");
Postbox p2 = CloudPostOffice.newPostbox("proj-xxx--postbox-2", "your-secret");

// postbox-2 listens for incoming messages
p2.listen(msg -> {
    System.out.println(msg); // {from=proj-xxx--postbox-1, msg=hello, ts=1234567890}
});

// postbox-1 sends a message to postbox-2
p1.send("proj-xxx--postbox-2", "hello");
```

The `listen` callback receives a `Map<String, Object>` with `"from"` (sender postbox ID), `"msg"` (payload), and `"ts"` (server timestamp as Long).

---

## Pub/Sub

Any postbox can publish or subscribe to any topic in the same project. No need to pre-create topics — they work on the fly.

```java
import com.cloudpostoffice.CloudPostOffice;
import com.cloudpostoffice.Postbox;

Postbox p1 = CloudPostOffice.newPostbox("proj-xxx--postbox-1", "your-secret");
Postbox p2 = CloudPostOffice.newPostbox("proj-xxx--postbox-2", "your-secret");

// p1 subscribes to a topic
p1.subscribe("news", (topic, msg) -> {
    System.out.println(topic + " " + msg);
});

// p2 publishes to the same topic
p2.publish("news", "CloudPostOffice is alive!");
```

The `subscribe` callback receives `(topicName, message)`.

---

## API

### `CloudPostOffice.newPostbox(postboxId, postboxSecret)`

Creates a postbox handle. Automatically authenticates and connects to the MQTT broker on first use.

```java
Postbox p = CloudPostOffice.newPostbox("proj-xxx--postbox-1", "my-secret");
```

---

### `postbox.send(to, msg)`

Sends a direct message to another postbox on the same account/project.

| Param | Type     | Description |
|-------|----------|-------------|
| `to`  | `String` | Target postbox ID |
| `msg` | `Object` | Message payload (any JSON-serialisable value) |

```java
p1.send("proj-xxx--postbox-2", "hello");
```

---

### `postbox.listen(callback)`

Registers a callback for messages addressed to this postbox. May be called multiple times to add multiple handlers.

`MessageHandler` is `void onMessage(Map<String, Object> message)`.

```java
p.listen(msg -> System.out.printf("Message from %s: %s%n", msg.get("from"), msg.get("msg")));
```

---

### `postbox.publish(topicName, message)`

Publishes a message to a named topic.

- Topic names must not contain `/`, `+`, `#`, or `--`.

```java
p.publish("alerts", Map.of("level", "warn", "text", "High temp"));
```

---

### `postbox.subscribe(topicName, callback)`

Subscribes to a named topic. Callback is called whenever a message is published to that topic.

`TopicHandler` is `void onMessage(String topicName, Object message)`.

```java
p.subscribe("alerts", (topic, msg) -> System.out.println(topic + " " + msg));
```

---

### `postbox.disconnect()`

Gracefully closes the MQTT connection.

```java
p.disconnect();
```

---

### `CloudPostOffice.configure(config)`

Overrides SDK-level options. Call before creating any postboxes.

```java
CloudPostOffice.configure(new CloudPostOffice.Config("https://cloudpostoffice.com"));
```

---

### `CloudPostOffice.publish(topicName, message)`

Publishes a message using the default postbox (the first one created).

---

### `CloudPostOffice.subscribe(topicName, callback)`

Subscribes using the default postbox.

---

## Error Types

| Type                        | Description |
|-----------------------------|-------------|
| `AuthenticationException`   | HTTP auth failed; has a `.getStatus()` int field |
| `ConnectionTimeoutException`| MQTT connection timed out |
| `CloudPostOfficeException`  | Base type for all SDK errors |

---

## Notes

- **Authentication tokens** are valid for 7 days. The SDK automatically re-authenticates on reconnect.
- Topic names must not contain `/`, `+`, `#`, or `--`.
- Uses **MQTT v3.1.1** with `CleanSession = false` (persistent sessions) and QoS 1 — identical offline-buffering behaviour to the Go SDK.
- Two clients cannot share the same postbox ID and secret at the same time within a project.

---

## Running Tests

### Unit tests (no credentials required)

```bash
gradle test
```

### Integration tests

Set your postbox credentials from the [dashboard](https://cloudpostoffice.com/app) as environment variables, then run:

```bash
export CPO_TEST_POSTBOX_1_ID=proj-xxx--postbox-1
export CPO_TEST_POSTBOX_1_SECRET=your-secret-1
export CPO_TEST_POSTBOX_2_ID=proj-xxx--postbox-2
export CPO_TEST_POSTBOX_2_SECRET=your-secret-2

gradle runAll
```

Alternatively, fill the values into `.env.test` at the repo root and `RunAll` will load them automatically (any variables already in the environment take precedence):

```bash
gradle runAll
```

Individual test programs read the same environment variables directly, so you can also run them standalone:

```bash
# Terminal 1 — start the subscriber
gradle runSub

# Terminal 2 — publish
gradle runPub
```

```bash
# Terminal 1 — postbox-2 listens
gradle runPostbox2

# Terminal 2 — postbox-1 sends
gradle runPostbox1
```

```bash
# Security test
gradle runUnauth
```

---

## Bootstrapping Gradle

If you don't have Gradle installed, install it first:

```bash
# Windows (Chocolatey)
choco install gradle

# Windows (Scoop)
scoop install gradle

# macOS / Linux (SDKMAN)
sdk install gradle
```

Then generate the Gradle wrapper:

```bash
gradle wrapper --gradle-version 8.11.1
```

After that, use `./gradlew` (Unix) or `gradlew.bat` (Windows) instead of `gradle`.

---

## Links

- [Dashboard](https://cloudpostoffice.com/app)
- [Documentation](https://cloudpostoffice.com/docs)
- [Issues](https://github.com/CloudPostOffice/java/issues)
- Email: [hi@cloudpostoffice.com](mailto:hi@cloudpostoffice.com)
