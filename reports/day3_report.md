# Aureus: Day 3 Report
## From Spring fundamentals to a working end-to-end Registration feature

**Project:** Aureus, Where Money Meets Elegance!
**Feature built:** Registration (`POST /api/auth/register`), working end to end: Postman to Controller to Service to Repository to MySQL and back
**Schema:** finalised on Day 2 (five tables). Day 3 builds the application on top of it.

**How this report is organised (read it top to bottom, it is one continuous flow)**

| Part | What it answers |
|---|---|
| 1. Fundamentals | Spring Framework vs Spring Core vs Spring MVC vs Spring Boot: what are these libraries? |
| 2. Project setup | Spring Initializr, Maven, `pom.xml`, each dependency and what it brings |
| 3. Database stack | Flyway, JPA, Hibernate, Spring Data JPA, repositories, derived queries |
| 4. Startup phase | 7 stages from the run command to "Tomcat started on port 8080" |
| 5. Request phase | What happens from clicking Send in Postman to the JSON response |
| 6. Service layer | `@Transactional`, `saveAndFlush`, and the custom debit query |
| 7. Reference | Where everything comes from, object lifetimes, FAQ, glossary, DB verification, full code |

Every time something appears (an annotation, a class, a behaviour), the report says **where it comes from** (which dependency or library), because that is the part that is invisible when you just read your own code.

---

# Table of contents

1. Fundamentals: Spring Framework, Core, MVC, Boot
2. Project setup: Initializr, Maven, `pom.xml`, dependencies
3. The database stack: Flyway, JPA, Hibernate, Spring Data JPA
4. **Startup phase** (7 stages)
5. **Request phase** (12 stages)
6. The service layer in short
7. Reference: sources table, lifetimes, FAQ, glossary, verification, code

---

# 1. Fundamentals: Spring Framework, Spring Core, Spring MVC, Spring Boot

All of these are **Java libraries** (JAR files) you add to a project. They are layered: each one builds on the one below.

```text
+---------------------------------------------------------------+
| Spring Boot            auto-configuration, starters,          |
|                        embedded server, "just run it"         |
+---------------------------------------------------------------+
| Spring MVC             web layer: controllers, REST, JSON     |
| Spring Data JPA        repositories                           |
| Spring Security        authentication and authorization       |
+---------------------------------------------------------------+
| Spring Core / Context  IoC container, beans, dependency       |
|                        injection (the foundation)             |
+---------------------------------------------------------------+
| Java (JDK 17)                                                 |
+---------------------------------------------------------------+
```

## 1.1 Spring Framework (the umbrella)

"Spring Framework" is the base project (started in 2003) that provides the core programming model for building Java applications. It is split into modules (separate JARs), e.g. `spring-core`, `spring-beans`, `spring-context`, `spring-webmvc`, `spring-tx`. When people say "Spring" they usually mean this framework plus the ecosystem around it.

## 1.2 Spring Core (IoC container and dependency injection)

This is the foundation everything else stands on. It gives you:

- **IoC (Inversion of Control):** you do not create and wire your objects with `new`; Spring creates them and connects them.
- **Beans:** objects managed by Spring.
- **Application context:** the container that holds the beans.
- **Dependency Injection (DI):** Spring passes an object's dependencies into it (usually through the constructor).

Modules: `spring-core`, `spring-beans`, `spring-context`.

Without IoC:
```java
AuthService service = new AuthServiceImpl(userRepo, accountRepo, ...);
AuthController controller = new AuthController(service);
```
With Spring Core: you only declare the classes and constructors. Spring builds the object graph.

## 1.3 Spring MVC (the web layer)

Spring MVC (module `spring-webmvc`) is Spring's web framework, built on the Java **Servlet API**. It turns HTTP requests into Java method calls and Java return values into HTTP responses. It provides:

- `DispatcherServlet`: the single front door for all requests
- Annotations: `@RestController`, `@RequestMapping`, `@GetMapping`, `@PostMapping`, `@RequestBody`
- JSON conversion (through Jackson), validation hooks, exception handling (`@RestControllerAdvice`)

MVC = Model-View-Controller. For REST APIs there is no "View" (HTML page); the response is JSON, so we use `@RestController`.

## 1.4 Spring Boot (the convenience layer)

Spring Boot is **not** a replacement for Spring; it is Spring plus automation. Before Boot, using Spring meant: pick compatible versions of ten libraries yourself, write XML or Java config for the datasource, the DispatcherServlet, the transaction manager, the JSON converter, then build a WAR and deploy it to a separately installed Tomcat.

Boot removes that work:

| Boot feature | What it does | Example in Aureus |
|---|---|---|
| **Starters** | One dependency pulls in a compatible bundle of libraries | `spring-boot-starter-data-jpa` brings Hibernate, Spring Data JPA, transactions, Hikari |
| **Auto-configuration** | Looks at your classpath and properties and configures beans for you | Sees MySQL driver + `spring.datasource.url`, so builds a datasource |
| **Embedded server** | Tomcat runs inside your app | `Tomcat started on port 8080` |
| **Dependency version management** | Boot's parent POM fixes compatible versions | You never write versions for starters |
| **Opinionated defaults** | Sensible defaults, overridable | Port 8080, Jackson for JSON |
| **Executable run** | `main()` plus embedded server | `mvnw spring-boot:run` |

## 1.5 The other Spring projects we use

| Project | Purpose | Used in Aureus for |
|---|---|---|
| **Spring Data JPA** | Repository abstraction over JPA | `UserRepository`, `AccountRepository`, etc. |
| **Spring Security** | Filters for authentication and authorization, password encoders | `SecurityConfig`, `PasswordEncoder` |
| **Spring Transaction (`spring-tx`)** | Declarative transactions | `@Transactional` on `register` |

## 1.6 One-line summary

> **Spring Core** creates and wires objects. **Spring MVC** turns HTTP into method calls. **Spring Data JPA** turns repository interfaces into database access. **Spring Security** guards requests. **Spring Boot** auto-configures all of it and runs it with an embedded server.

---

# 2. Project setup: Initializr, Maven, `pom.xml`, dependencies

## 2.1 Spring Initializr

Spring Initializr (start.spring.io) is a web tool that generates a ready-to-run project skeleton. Settings used:

| Setting | Value | Why |
|---|---|---|
| Project | Maven | Build tool |
| Language | Java | |
| Java | 17 | LTS version, required baseline for modern Spring |
| Spring Boot | 4.1.1 | Framework version |
| Packaging | JAR | Boot apps run as a plain JAR with embedded Tomcat (no WAR/external server) |
| Application name | Aureus | Gives main class `AureusApplication` |

It generates: `pom.xml`, `AureusApplication.java`, `application.properties`, the Maven Wrapper (`mvnw`, `mvnw.cmd`), and a `src/test` skeleton.

## 2.2 Maven (the build tool)

Maven does three jobs:

1. **Dependency management:** you list libraries in `pom.xml`; Maven downloads them (and *their* dependencies, called **transitive dependencies**) from **Maven Central** into a local cache.
2. **Build lifecycle:** compile, test, package.
3. **Plugins:** e.g. `spring-boot-maven-plugin`, which is what provides the `spring-boot:run` command.

**Maven Wrapper (`mvnw.cmd`):** a small script committed in the project that downloads the right Maven version automatically, so you don't need to install Maven globally. That's why we run `.\mvnw.cmd`, not `mvn`.

## 2.3 `pom.xml` (Project Object Model)

The `pom.xml` is the single file that describes the project to Maven. Its important sections:

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.1</version>
</parent>
```
**Parent POM:** declares Boot 4.1.1 and manages versions of hundreds of libraries. This is why individual starters below have no `<version>`.

```xml
<properties>
    <java.version>17</java.version>
</properties>
```

```xml
<dependencies>
    <!-- one block per dependency you chose in Initializr -->
</dependencies>
```

```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-maven-plugin</artifactId>
        </plugin>
    </plugins>
</build>
```
**Build plugin:** enables `spring-boot:run` and packaging an executable JAR.

> Note: Boot 4 reorganised its starters into a more modular layout, so exact artifact names in your `pom.xml` may differ slightly from the classic names used below. The concepts and what each dependency provides are the same. Open your own `pom.xml` and match each entry against the table.

## 2.4 Dependencies chosen and what each one brings

A **starter** is a dependency that is really a bundle: adding one line brings a set of compatible libraries.

| Initializr dependency | Typical starter | What it brings in | Used in Aureus for |
|---|---|---|---|
| **Spring Web** | `spring-boot-starter-web` (webmvc) | Spring MVC, **embedded Tomcat**, **Jackson** (JSON), servlet API | REST controllers, JSON, the server |
| **Spring Data JPA** | `spring-boot-starter-data-jpa` | Spring Data JPA, **Hibernate** (ORM), JPA API, **HikariCP**, `spring-tx` (transactions) | Entities, repositories, `@Transactional` |
| **MySQL Driver** | `mysql-connector-j` | JDBC driver for MySQL | Java to MySQL communication |
| **Validation** | `spring-boot-starter-validation` | Jakarta Bean Validation + **Hibernate Validator** | `@NotBlank`, `@Email`, `@Size`, `@Valid` |
| **Spring Security** | `spring-boot-starter-security` | Spring Security (filters), `spring-security-crypto` (BCrypt) | `SecurityConfig`, `PasswordEncoder` |
| **Flyway Migration** | `flyway-core` + `flyway-mysql` (Boot may bundle as a Flyway starter) | Flyway engine and its MySQL support | Versioned DB migrations |

Each starter also quietly pulls in `spring-boot-starter` (the core: Spring Core/Context, logging, YAML/properties support) and `spring-boot-autoconfigure` (the auto-configuration classes).

**Careful with a naming trap:** *Hibernate Validator* (validates request objects) and *Hibernate ORM* (talks to the DB) are two unrelated libraries from the same organisation. The validation starter brings the first; the JPA starter brings the second.

## 2.5 Package structure (inside `com.aureus.aureus`)

```text
com.aureus.aureus
 |-- AureusApplication.java     entry point (@SpringBootApplication)
 |-- controller/                REST endpoints (HTTP in/out)
 |-- service/                   business logic
 |-- repository/                database access interfaces
 |-- entity/                    classes mapped to tables, plus enums
 |-- dto/                       request/response objects
 |-- exception/                 custom exceptions + @RestControllerAdvice
 +-- config/                    SecurityConfig
```

**Why package location matters:** component scanning (stage 3 of startup) starts at the package of `AureusApplication` (`com.aureus.aureus`) and goes **downward**. Every package above is a sub-package, so everything is found. A class placed outside `com.aureus.aureus` would silently be ignored.

---

# 3. The database stack: Flyway, JPA, Hibernate, Spring Data JPA

This section explains the layers between your Java code and MySQL, in the order data flows down: schema first (Flyway), then mapping (JPA/Hibernate), then convenience (Spring Data).

## 3.1 Flyway: version control for the DB schema

**In one sentence:** Flyway is Git for your database schema. Each schema change is a numbered SQL file; Flyway applies the files in order, once each, and records what it applied.

```text
src/main/resources/db/migration/
    V1__init_schema.sql      creates the five tables (Day 2 DDL)
    V2__seed_system.sql      inserts admin user + SYSTEM account (99999999.99)
```

Rules:

- Name pattern: `V<version>__<description>.sql` (double underscore).
- Flyway records applied migrations in a table named `flyway_schema_history`, along with a checksum.
- On every startup it compares files against that table and applies only the new ones.
- **Never edit a migration that has already run** (checksum mismatch stops the app). Add `V3__...` instead.
- Flyway runs **before** Hibernate validates anything, so tables exist when entities are checked.

Why it matters for Aureus: the schema has CHECKs, UNIQUEs and FKs that must exist identically on every machine. Flyway makes that reproducible.

`V2` details: inserts admin (`admin@aureus.com`, BCrypt hash) first, then its `SYSTEM` account (balance `99999999.99`, the maximum for `DECIMAL(10,2)`), because the account's `user_id` foreign key requires the user to exist first.

## 3.2 The problem JPA solves

Java works with **objects**; MySQL works with **rows**. Doing it by hand with JDBC means writing SQL strings, setting parameters, reading result sets and copying columns into objects, for every operation. This mismatch has a name: the *object-relational impedance mismatch*. **ORM (Object-Relational Mapping)** automates it.

## 3.3 JPA vs Hibernate vs Spring Data JPA (three different things)

| Name | What it is | Analogy |
|---|---|---|
| **JPA** (Jakarta Persistence API) | A **specification**: a set of interfaces and annotations (`@Entity`, `@Id`, `EntityManager`). No implementation. | A standard / contract |
| **Hibernate** | The **implementation** of JPA. Actually generates SQL and talks to the DB through JDBC. | The engine that fulfils the contract |
| **Spring Data JPA** | A Spring layer on top of JPA that **generates repository implementations** for you | A power tool that removes boilerplate |

Stack, top to bottom:

```text
Your code:      userRepository.existsByEmail("a@b.com")
Spring Data JPA: parses the method name, builds a query
JPA API:         standard interfaces (EntityManager, JPQL)
Hibernate:       translates JPQL to MySQL SQL
JDBC:            standard Java database API
MySQL Driver:    speaks MySQL's network protocol
MySQL:           executes SQL
```

## 3.4 Entities

An **entity** is a Java class mapped to a table. Instances correspond to rows. Aureus has `User`, `Account`, `Transaction`, `LedgerEntry` (plus enums: `Role`, `UserStatus`, `AccountType`, `TransactionType`, `TransactionStatus`, `EntryType`).

Illustrative shape (the mechanics, not your exact class):

```java
@Entity
@Table(name = "Account")           // explicit, so it matches the real table name
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)   // DB AUTO_INCREMENT
    private Integer accountId;

    private Integer userId;

    @Enumerated(EnumType.STRING)   // store 'BANK'/'WALLET'/'SYSTEM', not 0/1/2
    private AccountType accountType;

    private BigDecimal balance;    // exact decimal for money
}
```

Notes:
- `@Entity`, `@Table`, `@Id`, `@GeneratedValue`, `@Enumerated` come from **`jakarta.persistence`** (the JPA API, brought by the JPA starter).
- `@Table(name = ...)` matters: Spring's default naming strategy would turn `LedgerEntry` into `ledger_entry`, but the real table is `LedgerEntry`.
- Your service code shows the entities use plain id fields (`new Account(userId, AccountType.BANK, amount)`), not object references to other entities.
- With `spring.jpa.hibernate.ddl-auto=validate`, Hibernate checks at startup that every entity matches its table and **fails fast** on a mismatch. It never creates or alters tables (Flyway owns the schema).

## 3.5 Spring Data JPA repositories

A repository is the object you use to read and write entities. With Spring Data JPA you write **only an interface**:

```java
public interface AccountRepository extends JpaRepository<Account, Integer> {
    List<Account> findByUserId(Integer userId);
    ...
}
```

You never write an implementation class. Who does? **Spring Data JPA, at startup**, creates a proxy object implementing your interface (backed by `SimpleJpaRepository`) and registers it as a bean (details in startup stage 5).

### What `extends JpaRepository<Account, Integer>` gives you

The two type parameters: the entity type (`Account`) and the type of its primary key (`Integer`). You inherit ready-made methods, with no code:

| Method | Purpose |
|---|---|
| `save(entity)` | INSERT (new) or UPDATE (existing) |
| `saveAndFlush(entity)` | `save` plus immediately send SQL to the DB |
| `saveAll(list)` | save many |
| `findById(id)` | SELECT by primary key, returns `Optional` |
| `findAll()` | SELECT all |
| `existsById(id)`, `count()` | checks |
| `deleteById(id)`, `delete(entity)` | DELETE |
| `flush()` | push pending changes to the DB |

### Derived query methods: the `findBy` magic

You can add methods just by **naming them correctly**. At startup Spring Data parses each method name into a query. The grammar:

```text
find  By  UserId  And  AccountType
 |    |     |      |       |
verb  |   property |    property
     start         connector
```

| Method name | Spring Data understands | Resulting SQL (conceptually) |
|---|---|---|
| `findByUserId(Integer)` | where `userId = ?` | `SELECT ... FROM Account WHERE user_id = ?` |
| `findByUserIdAndAccountType(Integer, AccountType)` | where `userId = ?` and `accountType = ?` | `... WHERE user_id = ? AND account_type = ?` |
| `findByAccountType(AccountType)` | where `accountType = ?` | `... WHERE account_type = ?` |
| `existsByEmail(String)` (UserRepository) | exists where `email = ?` | `SELECT 1 ... WHERE email = ? LIMIT 1` |

How it gets from name to SQL, step by step:

1. **Spring Data JPA** reads the method name, splits it into keyword and property parts (`By`, `And`, property names matched against the entity's fields), and builds a **JPQL** query (the object-oriented query language of JPA).
2. **Hibernate** translates that JPQL into **MySQL-dialect SQL** and maps column names via the entity annotations.
3. **JDBC + MySQL driver** send it to MySQL; rows come back, Hibernate turns them into `Account` objects.

The return type controls the shape: `List<Account>` (zero or many), `Optional<Account>` (zero or one), `boolean` for `exists...`, `int` for modifying queries.

**Property names must match field names in the entity** (`userId`, `accountType`), not column names. A typo fails **at startup** (Spring Data cannot build the query), not at runtime.

Other keywords available: `Or`, `Between`, `LessThan`, `GreaterThanEqual`, `Like`, `OrderBy...Desc`, `IsNull`, `In`, `Top`/`First`.

### Custom queries with `@Query`

When a name-based method can't express what you need, you write the query yourself. Our one custom method:

```java
@Modifying(flushAutomatically = true, clearAutomatically = true)
@Query("""
    UPDATE Account a
    SET a.balance = a.balance - :amount
    WHERE a.accountId = :accountId
      AND a.balance >= :amount
    """)
int debitIfSufficientBalance(
        @Param("accountId") Integer accountId,
        @Param("amount") BigDecimal amount
);
```

Why it exists and how it works is in section 6. For now, notice: the query is **JPQL** (it says `Account a` and `a.balance`, using the **entity name and field names**, not table and column names).

## 3.6 Where the database configuration lives

`src/main/resources/application.properties`:

```properties
spring.application.name=aureus

spring.datasource.url=jdbc:mysql://localhost:3306/aureus
spring.datasource.username=root
spring.datasource.password=YOUR_MYSQL_PASSWORD

spring.jpa.hibernate.ddl-auto=validate
spring.jpa.show-sql=true

spring.flyway.enabled=true
```

| Property | Meaning |
|---|---|
| `spring.datasource.*` | Which DB to connect to and credentials. Boot builds a datasource with **HikariCP** (a connection pool: a set of pre-opened DB connections reused by requests, because opening one per request is slow) |
| `ddl-auto=validate` | Hibernate **checks** entity-to-table match; never creates/changes tables |
| `show-sql=true` | Print Hibernate's SQL to the console (great for learning) |
| `flyway.enabled=true` | Run migrations at startup |

The `aureus` schema itself is created manually in MySQL Workbench before the first run. Flyway creates the **tables inside it**, not the schema.

---

# 4. STARTUP PHASE

Everything in this part happens **once**, before any request can be handled. At the end the app is running and waiting on port 8080.

```text
STARTUP PHASE
  Stage 1  You run the command
  Stage 2  IoC container / ApplicationContext is created
  Stage 3  Component scanning
  Stage 4  Auto-configuration
  Stage 5  Bean creation
  Stage 6  Spring prepares request-handling infrastructure
  Stage 7  Tomcat starts listening on port 8080
```

The single most important idea to carry through this phase:

> **Creating an object and calling its business methods are separate events.** At startup Spring creates objects and calls configuration code (constructors, `@Bean` methods). It does **not** call your business methods like `register()`.

---

## Stage 1: You run the command

```powershell
.\mvnw.cmd spring-boot:run
```

| Part | Meaning | Comes from |
|---|---|---|
| `mvnw.cmd` | Maven Wrapper: ensures the right Maven is available | Generated by Spring Initializr, lives in your project |
| `spring-boot:run` | A Maven **goal**: compile, build the classpath, launch the app | `spring-boot-maven-plugin` in your `pom.xml` |

What happens:

1. Maven reads `pom.xml`, resolves all dependencies (and transitive ones) from Maven Central, and compiles your code with Java 17.
2. It starts a JVM with the compiled classes and all dependency JARs on the **classpath**. (The classpath matters enormously: Boot's auto-configuration looks at it in stage 4.)
3. The JVM calls the `main` method of your main class:

```java
@SpringBootApplication
public class AureusApplication {
    public static void main(String[] args) {
        SpringApplication.run(AureusApplication.class, args);
    }
}
```

- Who calls `main()`? The JVM, launched by the Maven plugin.
- Who calls `SpringApplication.run(...)`? **You** do, in `main()`. This is the only line of your code that explicitly starts Spring. Everything after it is the framework working. (`SpringApplication` comes from `spring-boot`.)

`run()` does the following in order, which are our remaining six stages: build the context, scan, auto-configure, create beans, prepare web infrastructure, start Tomcat.

---

## Stage 2: IoC container / ApplicationContext is created

First, `run()` prepares the **Environment**: it loads `application.properties` (and environment variables, command-line args) so every later step can read the settings.

Then it creates the **ApplicationContext**, the IoC container from section 1.2. Because Spring MVC and servlet classes are on the classpath (from the web starter), Boot chooses a **web** application context, one that is also able to start and manage an embedded web server.

**What is the ApplicationContext?** A registry plus factory for beans:

- It stores **bean definitions** (recipes: "a bean of class X, with these dependencies").
- It creates the actual **bean instances** from those recipes.
- It holds them for the life of the app and injects them where needed.

At this moment it is **empty**. The next three stages fill it.

*Comes from:* `spring-context` (container) and `spring-boot` (choosing and launching the right context type).

---

## Stage 3: Component scanning

`@SpringBootApplication` on your main class is a shortcut for three annotations:

| Included annotation | Role |
|---|---|
| `@SpringBootConfiguration` | This class is itself a configuration source |
| `@ComponentScan` | **Stage 3**: scan for your beans |
| `@EnableAutoConfiguration` | **Stage 4**: auto-configure from the classpath |

`@ComponentScan` scans `com.aureus.aureus` and **all sub-packages**, looking for classes carrying `@Component` or an annotation built on it (a "stereotype"):

| Annotation on your class | Specialisation of | Meaning | Found in Aureus |
|---|---|---|---|
| `@Configuration` | `@Component` | Class that defines beans via `@Bean` methods | `SecurityConfig` |
| `@Service` | `@Component` | Business-logic bean | `AuthServiceImpl` |
| `@RestController` | `@Controller` + `@ResponseBody` | Web controller; return values become the HTTP body | `AuthController` |
| `@RestControllerAdvice` | `@Component` | Global exception handler for controllers | `GlobalExceptionHandler` |

For each class found, Spring registers a **bean definition**. **No object is created yet**; the container just now knows what it must create.

Two things component scanning does **not** pick up, which matters:

- **Repositories** are *interfaces*, so there is nothing to instantiate. Spring Data's own scanning finds them (stage 4, triggered by auto-configuration).
- **Entities** are not beans at all. They are found by a separate **entity scan** (from the same root package) so Hibernate knows which classes are mapped.

DTOs (`RegisterRequest`, `RegisterResponse`) and exceptions are plain classes with no stereotype annotation: never beans.

*Comes from:* annotations `@Service`, `@Configuration`, `@Component` in `spring-context`; `@RestController`, `@RestControllerAdvice` in `spring-webmvc`; `@SpringBootApplication` in `spring-boot-autoconfigure`.

---

## Stage 4: Auto-configuration

This is the part that feels like magic, so here is the mechanism.

### 4.1 How it works

Every Boot starter ships with **auto-configuration classes**: ordinary `@Configuration` classes full of `@Bean` methods, listed in a file inside the JAR (`META-INF/spring/...AutoConfiguration.imports`). `@EnableAutoConfiguration` makes Spring read all these lists from every JAR on the classpath and consider each class.

Each auto-configuration class is guarded by **conditions**:

| Condition | Meaning |
|---|---|
| `@ConditionalOnClass(X.class)` | Only if library X is on the classpath |
| `@ConditionalOnMissingBean` | Only if **you** haven't defined your own bean of that kind (**your config wins**) |
| `@ConditionalOnProperty(...)` | Only if a property is set |
| `@ConditionalOnWebApplication` | Only for web apps |

So Boot's logic is: *"If the library is here and you haven't configured it yourself, configure it with sensible defaults."* Adding or removing a dependency changes what gets configured. That is why the starters matter.

### 4.2 What gets auto-configured in Aureus

| Auto-configuration | Triggered because | Result |
|---|---|---|
| Datasource | MySQL driver + `spring.datasource.*` | A **HikariCP** connection-pool `DataSource` bean |
| **Flyway** | Flyway on classpath, `spring.flyway.enabled=true` | Flyway bean that **runs V1 and V2 migrations at startup** |
| Hibernate / JPA | JPA starter | `EntityManagerFactory` (Hibernate), with `ddl-auto=validate` |
| Spring Data JPA repositories | Spring Data on classpath | Scans for `JpaRepository` interfaces and creates their implementations |
| **Transaction management** | `spring-tx` present | A `PlatformTransactionManager` and the machinery that makes `@Transactional` work |
| Spring MVC | Web starter | **DispatcherServlet**, HandlerMapping, HandlerAdapter, argument resolvers, exception resolvers |
| **Jackson** | Jackson on classpath | JSON message converters |
| Bean Validation | Validator on classpath | A validator used for `@Valid` |
| **Embedded Tomcat** | Web starter (Tomcat) | A web-server factory that will start Tomcat |
| **Spring Security** | Security starter | The security servlet filter (`springSecurityFilterChain`) and defaults |

> Boot 4 split auto-configuration into smaller modules, so the exact class names and packages differ from older versions. The behaviour described above is the same.

### 4.3 "Your config wins": `@ConditionalOnMissingBean` in action

Spring Boot's default security setup would protect **every** endpoint and print a random password in the console. But you defined your own `SecurityFilterChain` bean and `PasswordEncoder` bean in `SecurityConfig`. Boot's default security config sees your beans and **backs off**. That is how your rules (`/api/auth/**` public) replace Boot's defaults.

---

## Stage 5: Bean creation

Now Spring turns **bean definitions into real objects** and wires them together. It resolves **dependencies first**, so creation happens in dependency order, not file order.

### 5.1 The creation order (conceptually)

```text
DataSource (Hikari pool)
   |
Flyway  ---- runs V1__init_schema.sql, V2__seed_system.sql against MySQL
   |
EntityManagerFactory (Hibernate)  ---- validates entities vs tables (ddl-auto=validate)
   |
TransactionManager
   |
Repository beans: UserRepository, AccountRepository,
                  TransactionRepository, LedgerEntryRepository
   |
PasswordEncoder (BCrypt)        <-- your @Bean in SecurityConfig
SecurityFilterChain             <-- your @Bean in SecurityConfig
   |
AuthServiceImpl   (needs 4 repositories + PasswordEncoder)
   |
AuthController   (needs AuthService)
GlobalExceptionHandler
```

Key moments:

- **Flyway runs here**, as a bean being initialised, and Hibernate is arranged to initialise **after** it. So by the time Hibernate validates, the tables exist.
- **Hibernate validation now has real entities to check** (on Day 3, before entities existed, it had nothing to compare). A wrong table/column name would crash startup here.
- **Repositories:** for each interface extending `JpaRepository`, Spring Data **generates a proxy class at runtime** implementing it, parses every derived method name (so a misspelled property name fails right here), and registers it as a bean.

### 5.2 `@Configuration` and `@Bean`: `SecurityConfig`

Spring instantiates `SecurityConfig`, then **calls its `@Bean` methods**; each returned object becomes a bean.

```java
@Bean
public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();      // you call new; Spring keeps the result as a bean
}
```

```java
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.csrf(csrf -> csrf.disable())
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/api/auth/**").permitAll()
            .anyRequest().authenticated());
    return http.build();
}
```

Spring calls this method **once**, passing an `HttpSecurity` builder as the argument. The full explanation of what this does, and why `build()` is called once, is in request stage R4, where the security filters are used. For now: this method runs at startup, and its product (a `SecurityFilterChain`) is stored as a bean.

### 5.3 Dependency injection of `AuthServiceImpl` and `AuthController`

Spring sees the constructors:

```java
public AuthServiceImpl(UserRepository userRepository,
                       AccountRepository accountRepository,
                       TransactionRepository transactionRepository,
                       LedgerEntryRepository ledgerEntryRepository,
                       PasswordEncoder passwordEncoder) { ... }

public AuthController(AuthService authService) { ... }
```

It finds a bean for each parameter type, creates them in the right order, and passes them in. This is **constructor injection**. Where does the `PasswordEncoder` for the service come from? From **your** `@Bean passwordEncoder()` in `SecurityConfig`.

### 5.4 The `@Transactional` proxy

`AuthServiceImpl.register` is annotated `@Transactional`. Spring cannot add "begin a transaction, commit or roll back" into your method directly, so it **wraps the bean in a proxy** object:

```text
AuthController  --calls-->  [ PROXY of AuthServiceImpl ]  --calls-->  real AuthServiceImpl.register()
                              begin transaction
                              (call real method)
                              commit  or  rollback on exception
```

The controller's `authService` field holds the **proxy**, not the raw object. Details in section 6.

### 5.5 Singletons

By default every bean is a **singleton**: one instance per application, created now, living until shutdown. Many concurrent requests share the same `AuthController` and `AuthServiceImpl` objects. Consequence: **never store request-specific data in fields of a bean.** Use method parameters and local variables.

### 5.6 What exists after stage 5

| Object | Bean? | Created when |
|---|---|---|
| `DataSource`, Flyway, `EntityManagerFactory`, `TransactionManager` | Yes (auto-configured) | Startup |
| 4 repository proxies | Yes (generated) | Startup |
| `PasswordEncoder`, `SecurityFilterChain` | Yes (your `@Bean`s) | Startup |
| `AuthServiceImpl` (as transactional proxy), `AuthController`, `GlobalExceptionHandler` | Yes (scanned) | Startup |
| `RegisterRequest`, `RegisterResponse`, entities, `ResponseEntity`, `BigDecimal` values | **No** | Created per request, with `new` or by Jackson/Hibernate |

`register()` has **not** been called. Only constructors and `@Bean` methods have run.

---

## Stage 6: Spring prepares the request-handling infrastructure

With beans in place, Spring Boot assembles everything needed to turn HTTP into method calls. All of this is set up **now**, so that at request time it only has to *look things up*.

### 6.1 DispatcherServlet is registered

Spring MVC's `DispatcherServlet` (full explanation in R5) is created and registered with the web server, mapped to `/` so it receives all URLs. You never write or configure it.
*Comes from:* `spring-webmvc`, registered by Boot's MVC auto-configuration.

### 6.2 The URL mapping table is built

Spring MVC inspects every controller bean for mapping annotations and combines class-level and method-level paths:

```java
@RestController
@RequestMapping("/api/auth")        // class-level base path
public class AuthController {

    @PostMapping("/register")       // method-level: verb + sub-path
    public ResponseEntity<RegisterResponse> register(...)
}
```

Resulting entry in the lookup table (kept in memory):

```text
POST /api/auth/register   ->   AuthController.register(...)
```

This table is built **once**, now. At request time, "HandlerMapping" only does a lookup in it.

### 6.3 Other web components assembled

| Component | Role (explained at the request stage where it's used) |
|---|---|
| HandlerMapping | URL + HTTP verb to controller method |
| HandlerAdapter | Actually invokes the method, preparing its arguments |
| Argument resolvers | Produce method parameters, e.g. `@RequestBody` |
| HttpMessageConverters (Jackson) | JSON to Java object and back |
| Validator | Runs `@Valid` rules |
| Exception resolvers | Send exceptions to `@RestControllerAdvice` |

### 6.4 The security filter is registered with Tomcat

Boot registers a special servlet filter named **`springSecurityFilterChain`** with the web server. It sits **in front of** the DispatcherServlet. Behind it is a Spring Security object (`FilterChainProxy`) that holds your `SecurityFilterChain` bean(s). (R4 explains this fully.)
*Comes from:* Spring Security, via Boot's security auto-configuration.

---

## Stage 7: Tomcat starts listening on port 8080

Finally the application context finishes, and the **embedded web server** starts:

- Tomcat is created from the factory defined by auto-configuration.
- DispatcherServlet and the security filter are registered inside it.
- Tomcat opens **TCP port 8080** and creates a **pool of worker threads** to handle requests.

You see:

```text
Tomcat started on port 8080 (http)
Started AureusApplication in X seconds
```

**What is Tomcat?** A web server and **servlet container**: a program that opens a network port, accepts connections, parses raw HTTP into structured Java objects, and hands them to servlets and filters. **Embedded** means it runs inside your application as a library (no separate install, no WAR deployment).
*Comes from:* `spring-boot-starter-tomcat`, pulled in by the web starter.

The app now idles, waiting for requests. Everything from here on is the **request phase**.

### Startup summary

| Stage | What happens | Key source |
|---|---|---|
| 1 | `mvnw spring-boot:run` launches JVM; `main()` calls `SpringApplication.run` | Maven Wrapper, `spring-boot-maven-plugin`, `spring-boot` |
| 2 | Environment loaded; empty ApplicationContext created | `spring-context`, `spring-boot` |
| 3 | `@ComponentScan` registers bean definitions for your annotated classes | `@SpringBootApplication`, stereotype annotations |
| 4 | Auto-configuration conditionally configures datasource, Flyway, Hibernate, MVC, Jackson, Tomcat, Security | `spring-boot-autoconfigure`, starters |
| 5 | Beans created in dependency order: Flyway migrates, Hibernate validates, repository proxies, your `@Bean`s, service (proxied), controller | Spring Core, Spring Data JPA |
| 6 | URL mapping table built, DispatcherServlet and security filter registered | `spring-webmvc`, Spring Security |
| 7 | Tomcat starts on 8080 | `spring-boot-starter-tomcat` |

---

# 5. REQUEST PHASE

The application is running. You click **Send** in Postman:

```text
POST http://localhost:8080/api/auth/register
Content-Type: application/json

{
  "email": "test@example.com",
  "password": "Password123"
}
```

**Postman knows nothing about Java, Spring or your classes.** It only sends bytes. The question "who calls `AuthController.register()`?" has this answer: *Spring MVC translates the HTTP request into a method call.* The stages:

```text
R1   Postman sends HTTP over TCP/IP to port 8080
R2   Tomcat receives it
R3   Tomcat creates HttpServletRequest + HttpServletResponse
R4   Security filters execute (first!)
R5   DispatcherServlet receives the request
R6   HandlerMapping finds the controller method
R7   Jackson converts JSON into RegisterRequest (@RequestBody)
R8   Validator runs (@Valid)
R9   HandlerAdapter invokes AuthController.register(...)
R10  Service layer does the work and returns a RegisterResponse
R11  ResponseEntity -> JSON -> HTTP response back to Postman
R12  (alternate path) Exceptions -> GlobalExceptionHandler
```

---

## R1: Postman sends the HTTP data over TCP/IP to port 8080

HTTP is a text protocol that travels over **TCP/IP**:

- **IP** gets the packets to the right machine (`localhost` = your own machine).
- **TCP** gives a reliable connection to a specific **port** (8080 = Tomcat's listening port).
- **HTTP** is the format of the message inside.

What actually goes over the connection is plain text:

```text
POST /api/auth/register HTTP/1.1
Host: localhost:8080
Content-Type: application/json
Content-Length: 58

{"email":"test@example.com","password":"Password123"}
```

Parts: **request line** (method, path, version), **headers** (metadata), a blank line, then the **body**. The `Content-Type: application/json` header is what later tells Spring to use the JSON converter.

---

## R2: Tomcat receives it

Tomcat's network connector accepts the TCP connection on port 8080 and reads the bytes. It parses the raw text into its parts (method, path, headers, body) and assigns the request to a **worker thread** from its thread pool. Each request is handled on its own thread, so many requests run **at the same time**, all sharing the same singleton beans. (This is why bean fields must not hold request data, and why money-safety later needs database locking and transactions.)

---

## R3: Tomcat creates `HttpServletRequest` and `HttpServletResponse`

**What is a servlet?** The Servlet API (`jakarta.servlet`) is Java's standard way to handle HTTP. A **servlet** is a class with a method like `service(request, response)` that Tomcat calls for each request. Tomcat only knows how to talk to servlets and filters; it knows nothing about Spring.

Tomcat wraps the parsed HTTP data in two Java objects:

| Object | Holds |
|---|---|
| `HttpServletRequest` | Method, path, headers, body stream, session, etc. |
| `HttpServletResponse` | Where status, headers and body will be written |

Tomcat then runs the request through a **filter chain** and finally into a servlet:

```text
 request ----> [Filter 1] -> [Filter 2] -> ... -> [Servlet] -----> your code
              (security filters live here)       (DispatcherServlet)
```

A **filter** runs before the servlet and can stop the request (e.g. reject it) so the servlet is never reached. **Spring Security is implemented as filters**, which is why security runs before any controller.

---

## R4: Security filters execute first

Tomcat calls the registered `springSecurityFilterChain` filter (registered in startup stage 6). Behind it:

```text
Tomcat  ->  springSecurityFilterChain (a DelegatingFilterProxy)
             ->  FilterChainProxy   (Spring Security bean that holds all SecurityFilterChain beans)
                  ->  YOUR SecurityFilterChain  (the one built from SecurityConfig)
                       ->  its ordered list of security filters
```

`FilterChainProxy` picks the `SecurityFilterChain` whose request matcher fits the incoming request (yours matches everything) and runs the request through that chain's filters.

### R4.1 The code that configured those rules

This is the file, **`config/SecurityConfig.java`**:

```java
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http)
            throws Exception {

        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/**").permitAll()
                .anyRequest().authenticated()
            );

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
```

### R4.2 Configuration time vs request time (the key distinction)

**This code did not run just now.** It ran **once, at startup** (stage 5). It does not *check* anything. It **declares rules**. At request time, Spring Security *applies* the rules that were stored. Analogy: writing a security policy handbook once (`SecurityConfig`) versus a guard consulting it for every visitor (the filters at request time).

| | Configuration time | Request time |
|---|---|---|
| When | Startup, once | Every request |
| Who runs it | Spring calls your `@Bean` method | Tomcat + Spring Security filters |
| What happens | Rules are declared; chain is built | Stored rules are applied to the real request |

### R4.3 Line by line

**`HttpSecurity http` (the parameter):** you never write `new HttpSecurity()`. Spring Security's auto-configuration registers `HttpSecurity` as a **builder bean** and Spring **injects it into your method as a parameter** (dependency injection into a `@Bean` method). It arrives pre-wired with shared infrastructure (authentication setup, shared objects, defaults) that would be tedious to build by hand. You only customise it.

**What is a builder?** An object you configure step by step, then ask for the finished product. Like `new StringBuilder().append(a).append(b).toString()`.

| Role | Object | Nature |
|---|---|---|
| Builder | `HttpSecurity` | Mutable, used only during setup, holds your configuration choices |
| Product | `SecurityFilterChain` | The finished, ordered list of security filters |

**`.csrf(csrf -> csrf.disable())`:** tells the builder to leave out CSRF protection when assembling the filters. The `csrf -> csrf.disable()` is a **lambda** (an inline function): Spring Security hands you a small configurer object named `csrf`, and your lambda configures it. CSRF attacks exploit browsers automatically attaching **cookies**. Aureus will be a stateless API with bearer tokens in a header, which browsers do not attach automatically, so CSRF protection is not needed. If cookie-based auth were ever used, this would need rethinking. "Disable CSRF" is not universal advice.

**`.authorizeHttpRequests(auth -> auth ... )`:** tells the builder to include an authorization filter configured with the following rules, which are checked **in order, first match wins**:

1. `.requestMatchers("/api/auth/**").permitAll()`: any path starting with `/api/auth/` is allowed without authentication. (`/**` means "anything below".) Registration must be reachable by people with no account.
2. `.anyRequest().authenticated()`: everything else requires authentication. This is the catch-all, so it must come **last**. It makes the app **default-deny**.

`requestMatchers(...)` is **you writing a rule**, not the app checking a URL right now.

**`return http.build();`** takes everything collected in the builder and **constructs the final `SecurityFilterChain`**: a concrete ordered list of filter objects (header writers, authorization filter, exception translation, etc.) with your rules built in.

### R4.4 Why is `build()` called only once, yet used for every request?

- **Assembling is a one-time cost.** The rules don't change between requests, so rebuilding each time would be slow and pointless.
- **The product is read-only in practice and thread-safe.** Many request threads can safely run through the same chain at once.
- **Because of `@Bean`**, the returned chain is stored as a **singleton bean** for the app's lifetime, and registered via `FilterChainProxy`.

Analogy: you design and build a car once (`build()`); then you drive it thousands of times. You do not rebuild it for every trip.

Lifecycle of that chain:

```text
STARTUP (once)
   Spring calls securityFilterChain(http)
   your code configures the builder
   http.build()  ->  SecurityFilterChain object  ->  stored as a bean
   Spring Security's FilterChainProxy picks it up

EVERY REQUEST (many times)
   Tomcat -> springSecurityFilterChain -> FilterChainProxy -> your chain's filters
   same chain object, reused; your method is NOT called again
```

The same logic applies to `passwordEncoder()`: called once, and that one `BCryptPasswordEncoder` object serves every future registration (it is thread-safe).

### R4.5 Applying the rule to our request

```text
Request path: /api/auth/register

rule 1:  /api/auth/**  matches   ->  ALLOWED (permitAll)
rule 2:  anyRequest().authenticated()   never reached
```

- **`permitAll()` does not skip security.** The request still goes through every filter in the chain; the *authorization decision* for this path is just "allowed without login".
- CSRF is not checked, because it was disabled at configuration time.
- For a protected URL like `/api/wallet/balance` (no token), rule 1 doesn't match, rule 2 applies, authentication is missing, and the filter **stops the request** with an error response; the controller is never called. (Right now that default error is a 403; we will make it a clean 401 with the JWT work.)

The request passes the security filters and continues onward.

---

## R5: DispatcherServlet receives the request

**What is it?** Tomcat only calls servlets. Spring MVC presents itself to Tomcat as **one** servlet at the front of everything: the **`DispatcherServlet`**. This is the **Front Controller pattern**: a single entry point receives every request and delegates to the right handler, instead of having one servlet per URL.

- Registered at startup (stage 6), mapped to `/` (all URLs).
- *Comes from:* `spring-webmvc`, set up by Boot's MVC auto-configuration. You never write it.

The DispatcherServlet does not do the work itself. It **coordinates specialists**:

| Specialist | Job | For our request |
|---|---|---|
| **HandlerMapping** | Find which controller method handles this request | `POST /api/auth/register` to `AuthController.register` |
| **HandlerAdapter** | Invoke that method, preparing its arguments | Calls `register(request)` |
| **Argument resolvers** | Produce each method parameter | `@RequestBody` reads and converts the body |
| **HttpMessageConverters** | JSON text to/from Java objects | Jackson |
| **Validator** | Apply `@Valid` rules | Checks email and password |
| **Return-value handlers** | Process what the method returns | `ResponseEntity` to status + body |
| **Exception resolvers** | Convert exceptions to responses | `GlobalExceptionHandler` |

Inside the DispatcherServlet:

```text
request arrives
 -> HandlerMapping: which handler?                    AuthController.register
 -> HandlerAdapter: prepare arguments
      -> argument resolver for @RequestBody: Jackson builds RegisterRequest
      -> @Valid: run validator           (invalid -> exception -> R12)
 -> invoke controller method
 -> return-value handler: write ResponseEntity as JSON
if any exception: exception resolvers -> GlobalExceptionHandler
```

---

## R6: HandlerMapping finds the controller method

`RequestMappingHandlerMapping` consults the table built at startup (stage 6.2):

```text
POST /api/auth/register  ->  AuthController.register(...)
```

It matches on **both HTTP verb and path**. The class-level `@RequestMapping("/api/auth")` plus method-level `@PostMapping("/register")` combine into the full path. A `GET` to the same path would not match and would return `405 Method Not Allowed`; an unknown path returns `404`. No new mapping is built per request: it is a lookup.

---

## R7: Jackson converts JSON into your DTO (because of `@RequestBody`)

The controller method's signature:

```java
@PostMapping("/register")
public ResponseEntity<RegisterResponse> register(
        @Valid @RequestBody RegisterRequest request) {
```

Before Spring can call this method it must produce the `request` argument. **`@RequestBody`** tells Spring: *take the HTTP body and convert it into this parameter's type.*

The conversion is done by an **HttpMessageConverter**; for JSON (`Content-Type: application/json`) that is **Jackson** (brought by the web starter; in Boot 4 this is the newer Jackson 3 line, same behaviour for our purposes). This is called **deserialization**.

The DTO:

```java
public class RegisterRequest {

    @NotBlank
    @Email
    private String email;

    @NotBlank
    @Size(min = 8)
    private String password;

    // getters and setters
}
```

What Jackson does:

1. Creates a `RegisterRequest` object using its no-argument constructor.
2. Matches JSON keys to properties by name (`"email"` to `email`) and fills them through the setters.

Equivalent result:

```java
RegisterRequest request = new RegisterRequest();
request.setEmail("test@example.com");
request.setPassword("Password123");
```

You never write those lines. Practical consequences:

- The DTO needs a **no-arg constructor and setters**, and JSON key names must **match field names**, or fields stay null.
- The result is a plain in-memory Java object, **not** a database record.
- Malformed JSON (e.g. a missing brace) fails here with a `400`, before validation.

---

## R8: The validator runs

**`@Valid`** on the parameter tells Spring: after conversion, run **Bean Validation** on the object using the annotations on its fields.

| Annotation | Rule |
|---|---|
| `@NotBlank` (email) | Not null, not empty, not only spaces |
| `@Email` | Looks like an email address |
| `@NotBlank` (password) | Not null / empty / spaces |
| `@Size(min = 8)` | At least 8 characters |

*Comes from:* the Jakarta Bean Validation API and **Hibernate Validator**, both brought by the validation starter. (Remember: unrelated to Hibernate ORM.)

```text
JSON -> Jackson builds RegisterRequest -> validator checks the object
          valid?    -> invoke the controller method
          invalid?  -> throw MethodArgumentNotValidException
                       -> method body never runs -> GlobalExceptionHandler -> 400
```

These are **format checks only**. "Is this email already registered?" is a **business** check that needs the database; that happens in the service.

---

## R9: HandlerAdapter invokes `AuthController.register(...)`

The `RequestMappingHandlerAdapter` calls your method using reflection, passing the validated `RegisterRequest`. This is the first line of **your own code** in this request.

```java
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(
            @Valid @RequestBody RegisterRequest request) {

        RegisterResponse response = authService.register(request);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
```

- **Where does `authService` come from?** Constructor injection at startup (stage 5). It is the shared singleton (as a `@Transactional` proxy), not created per request.
- **Why does calling the interface run `AuthServiceImpl`?** The field is typed `AuthService` (an interface) but holds the implementation object. Java's **polymorphism** runs the implementation's method. Spring chose *which object* to inject; Java chooses *which method body* to run. Using an interface also made the Day 3 flow possible: the controller was built first against a stub, and the real service later replaced it without touching the controller.

---

## R10: The service layer

`authService.register(request)` runs the real registration logic. This is covered in section 6. In one sentence: inside **one database transaction** it normalises and checks the email, hashes the password, creates the user, the BANK and WALLET accounts, safely debits the SYSTEM account, and records the `OPENING_BALANCE` transaction with its two ledger entries, then returns a `RegisterResponse`. Back in the controller, `response` now holds that object.

---

## R11: The response goes back to Postman

### R11.1 `ResponseEntity`

```java
return ResponseEntity.status(HttpStatus.CREATED).body(response);
```

`ResponseEntity` represents the **entire HTTP response**: status code, headers and body.

- `status(HttpStatus.CREATED)` = status **201 Created** (the correct code when a new resource is created)
- `.body(response)` = the body object

Returning `ResponseEntity` (rather than the bare DTO) lets you control the status; a bare DTO would default to 200.

### R11.2 Serialization (Java to JSON)

Because the class is a `@RestController`, the return value is written **to the HTTP body** (not treated as an HTML page name). The return-value handler uses a message converter: **Jackson reads the getters** of `RegisterResponse` and writes JSON. (So the response DTO needs getters; with none, Jackson has nothing to read.)

```json
{
  "userId": 7,
  "email": "test@example.com",
  "bankBalance": 5000.00,
  "walletBalance": 0.00
}
```

### R11.3 Back out through the layers

```text
RegisterResponse -> Jackson -> JSON text
   -> written into HttpServletResponse (status 201, Content-Type: application/json)
   -> back through the security filters (e.g. security headers are added)
   -> Tomcat serialises the response as HTTP text and writes it to the TCP connection
   -> Postman displays "201 Created" and the body
```

The worker thread is returned to Tomcat's pool, ready for the next request.

---

## R12: The alternate path: exceptions

If something throws, the flow jumps to **exception resolvers**, which delegate to the class annotated `@RestControllerAdvice`:

```java
@RestControllerAdvice
public class GlobalExceptionHandler {
    // @ExceptionHandler methods map exceptions to consistent JSON error responses
}
```

| Trigger | Exception | Response |
|---|---|---|
| Invalid email / short password | `MethodArgumentNotValidException` (thrown by validation, R8) | 400 |
| Email already registered | `DuplicateEmailException` (thrown by the service) | 409 |

The `@RestControllerAdvice` class is a bean created at startup; its methods run **only** when a matching exception occurs. One central place for errors means every endpoint returns errors in the same JSON shape.

---

## Request phase: the complete synchronous timeline

```text
Postman
  | POST /api/auth/register   (HTTP text over TCP, port 8080)
  v
Tomcat (embedded)                       [R2]
  | worker thread; builds HttpServletRequest / HttpServletResponse   [R3]
  v
springSecurityFilterChain -> FilterChainProxy -> your SecurityFilterChain  [R4]
  | rule: /api/auth/** -> permitAll  => continue
  v
DispatcherServlet                       [R5]
  | HandlerMapping: POST /api/auth/register -> AuthController.register   [R6]
  | Jackson: JSON -> RegisterRequest (@RequestBody)                      [R7]
  | Validator: @Valid                                                   [R8]
  | HandlerAdapter invokes AuthController.register(request)             [R9]
  v
AuthController.register
  | authService.register(request)
  v
[@Transactional proxy] begins DB transaction                             [R10]
  AuthServiceImpl.register -> repositories -> Hibernate -> JDBC -> MySQL
[@Transactional proxy] commits (or rolls back on exception)
  |
  v
ResponseEntity 201 + RegisterResponse                                    [R11]
  | Jackson: RegisterResponse -> JSON
  v
back through filters -> Tomcat -> TCP -> Postman: 201 Created + JSON
```

---

# 6. The service layer in short

You understand the code of `AuthServiceImpl`; here are only the concepts worth being able to explain. (Full code is in Appendix A.)

## 6.1 The flow in seven steps

| Step | Action | Layer used |
|---|---|---|
| 1 | Trim and lowercase email; `existsByEmail`; if exists, throw `DuplicateEmailException` | `UserRepository` |
| 2 | BCrypt-hash the password; `saveAndFlush` the new `User` (catch DB duplicate for the race case) | `PasswordEncoder`, `UserRepository` |
| 3 | Create BANK (₹5,000.00) and WALLET (₹0.00) accounts | `AccountRepository` |
| 4 | Find the single SYSTEM account; `debitIfSufficientBalance` by 5,000 | `AccountRepository` custom query |
| 5 | Save `OPENING_BALANCE` transaction SYSTEM to BANK, key `OPENING-<userId>` | `TransactionRepository` |
| 6 | Save DEBIT (SYSTEM) and CREDIT (BANK) ledger entries | `LedgerEntryRepository` |
| 7 | Build and return `RegisterResponse` | |

## 6.2 What `@Transactional` implies

A **database transaction** is a group of operations that succeed or fail as a unit (**atomicity**): either every change is committed, or none is.

`@Transactional` (from `spring-tx`, brought by the JPA starter; `org.springframework.transaction.annotation.Transactional`) means:

1. **Spring begins a transaction before `register` starts** and **commits when it returns normally**.
2. If a **runtime (unchecked) exception** escapes, Spring **rolls back** everything: user, accounts, SYSTEM debit, transaction and ledger rows all disappear. (By default, checked exceptions do *not* trigger rollback; ours are runtime exceptions.)
3. All repository calls inside the method share **the same DB connection and transaction**.

**How it works: the proxy.** At startup (stage 5.4) Spring wraps `AuthServiceImpl` in a proxy. The controller calls the proxy, which starts the transaction, calls your real method, then commits or rolls back. Two consequences:

- **Self-invocation does not work:** if one method inside `AuthServiceImpl` calls another `@Transactional` method *of the same class*, the call bypasses the proxy and the annotation has no effect.
- A transaction only exists for calls **entering through the proxy**.

**Why it is essential in Aureus:** registration touches five tables. If the ledger insert failed after the SYSTEM account had been debited and the user created, you would have a user with money that has no ledger record, and a SYSTEM balance that dropped for nothing. With `@Transactional`, either everything exists or nothing does. This is the foundation of the "never create money silently" principle.

Also note: data written inside the transaction is **not visible to other transactions until commit**, and the commit happens when `register` returns, **after** the last line of your method.

## 6.3 `save` vs `saveAndFlush`, and what "flush" means

Hibernate does not send each change to MySQL immediately. It keeps pending changes in a memory area called the **persistence context** (first-level cache) and sends them in a batch later: this is **flushing**.

| Call | Behaviour |
|---|---|
| `save(entity)` | Schedules the insert/update in the persistence context; the SQL **may be sent later** (at commit, or before a query) |
| `saveAndFlush(entity)` | `save` **plus an immediate flush**: the SQL is sent to MySQL **now** |

**A flush is not a commit.** After `saveAndFlush`, the row exists inside your open transaction (visible to you), but is not permanent or visible to others until the transaction commits; a later exception still rolls it back.

Why we use `saveAndFlush` in `register`:

1. **Catch constraint violations immediately.** For the user insert, the DB's `UNIQUE(email)` check happens at flush time. Flushing inside the `try` block lets us catch `DataIntegrityViolationException` and convert it to `DuplicateEmailException` (409). Without it, the violation might only surface at commit, outside the `try`, as an unhandled 500. This covers the **race condition** where two requests with the same email both pass `existsByEmail` and both try to insert.
2. **Get the generated id now.** `AUTO_INCREMENT` ids exist after the insert; we need `userId` and the account ids for the following steps.
3. **Make accounts visible to the custom UPDATE.** The conditional debit is executed as SQL; pending inserts need to be flushed first for consistent state (the query's `flushAutomatically = true` also guarantees this).

`saveAll(List.of(debitEntry, creditEntry))` at the end does not need an immediate flush: nothing after it needs the ids, and the commit sends the SQL.

## 6.4 The custom method: `debitIfSufficientBalance`

```java
@Modifying(flushAutomatically = true, clearAutomatically = true)
@Query("""
    UPDATE Account a
    SET a.balance = a.balance - :amount
    WHERE a.accountId = :accountId
      AND a.balance >= :amount
    """)
int debitIfSufficientBalance(@Param("accountId") Integer accountId,
                             @Param("amount") BigDecimal amount);
```

**Why not just read the balance, subtract in Java and save?**

```text
Request A reads SYSTEM balance = 5,000     Request B reads SYSTEM balance = 5,000
A computes 5,000 - 5,000 = 0, saves 0      B computes 5,000 - 5,000 = 0, saves 0
-> two registrations were funded, but only 5,000 was deducted. Money was created.
```

This is the **lost update** problem under concurrency. The custom method avoids it by doing the check and the subtraction **inside a single SQL statement**:

```sql
UPDATE Account SET balance = balance - 5000
WHERE account_id = ? AND balance >= 5000
```

MySQL (InnoDB) takes a **row lock** on that row for the statement, so concurrent updates are serialised and each sees the up-to-date balance. If the balance is insufficient, the `WHERE` clause matches **zero rows** and nothing changes.

**The return value is the number of rows updated:**

- `1` = debit applied
- `0` = not applied (insufficient funds, or no such account)

The service checks `rowsUpdated != 1` and throws, which rolls back the whole registration. This pattern is a **conditional (compare-and-set) update**, and it will be reused for the real wallet transfers later. The DB's `CHECK (balance >= 0)` is a second safety net underneath.

**Annotation by annotation:**

| Part | Meaning |
|---|---|
| `@Query("""...""")` | Defines the query as **JPQL** (Java text block). Uses **entity name** `Account` and **field names** `balance`, `accountId`, not table/column names |
| `:amount`, `:accountId` | Named parameters, safely bound (prevents SQL injection) |
| `@Param("amount")` | Links the method parameter to the `:amount` placeholder |
| `@Modifying` | Required because the query changes data (`UPDATE`/`DELETE`), not a `SELECT`; it makes Spring Data execute it as an update and return the affected row count |
| `flushAutomatically = true` | Before running the UPDATE, **flush pending changes** (e.g. the just-saved accounts) so the DB state is up to date |
| `clearAutomatically = true` | After the UPDATE, **clear the persistence context**. A bulk JPQL update goes straight to the DB, bypassing Hibernate's cache; without clearing, any cached `Account` object would still hold the old balance. |
| `int` return | Number of rows affected |

**Side effect to know:** because the persistence context is cleared, entity objects loaded before the call (like `bankAccount`) become **detached**. Reading their already-loaded fields (`getAccountId()`, `getBalance()`) still works, which is all the rest of `register` needs. If you later need fresh data, re-load it from the repository.

## 6.5 Other things worth remembering from the service

- **`passwordEncoder.encode(...)`**: BCrypt produces a salted one-way hash; the raw password is never stored. `PasswordEncoder` is the bean created in `SecurityConfig`, injected here.
- **Constants as `BigDecimal`**: money uses `BigDecimal` (exact), never `double`.
- **Deterministic idempotency key** `"OPENING-" + userId`: the UNIQUE constraint on `idempotency_key` guarantees a user can never get two opening-balance transactions.
- **Invariant check** `systemAccounts.size() != 1`: the schema doesn't enforce "exactly one SYSTEM account" so the service verifies the seed data and fails loudly if it is wrong.
- **Rollback on failure** is automatic for any `RuntimeException`, including `DuplicateEmailException` and `IllegalStateException`.

---

# 7. Reference

## 7.1 Where does everything come from? (the master table)

| You see in code / logs | Comes from | Brought in by |
|---|---|---|
| `SpringApplication`, `@SpringBootApplication` | `spring-boot`, `spring-boot-autoconfigure` | Every starter |
| `@Service`, `@Component`, `@Configuration`, `@Bean`, DI, ApplicationContext | `spring-context` (Spring Core) | Every starter |
| `@RestController`, `@RequestMapping`, `@PostMapping`, `@RequestBody`, `ResponseEntity`, `DispatcherServlet`, `@RestControllerAdvice` | `spring-webmvc` | Spring Web starter |
| Embedded Tomcat | `tomcat-embed-*` via `spring-boot-starter-tomcat` | Spring Web starter |
| Jackson (JSON conversion) | Jackson libraries | Spring Web starter |
| Servlet API (`HttpServletRequest`, filters) | `jakarta.servlet` | Spring Web starter / Tomcat |
| `@Valid`, `@NotBlank`, `@Email`, `@Size` | Jakarta Validation API + Hibernate Validator | Validation starter |
| `JpaRepository`, `@Query`, `@Modifying`, `@Param` | Spring Data JPA | Data JPA starter |
| `@Entity`, `@Table`, `@Id`, `@Enumerated` | `jakarta.persistence` (JPA API) | Data JPA starter |
| Hibernate (generates SQL, validates schema) | Hibernate ORM | Data JPA starter |
| HikariCP (connection pool) | HikariCP | Data JPA starter |
| `@Transactional`, `DataIntegrityViolationException` | `spring-tx` | Data JPA starter |
| MySQL connectivity | `mysql-connector-j` | MySQL Driver dependency |
| Flyway migrations, `flyway_schema_history` | Flyway | Flyway dependency (+ MySQL module) |
| `HttpSecurity`, `SecurityFilterChain`, `requestMatchers`, filter chain | Spring Security | Security starter |
| `PasswordEncoder`, `BCryptPasswordEncoder` | `spring-security-crypto` | Security starter |
| `mvnw`, `pom.xml`, `spring-boot:run` | Maven Wrapper, `spring-boot-maven-plugin` | Spring Initializr |

## 7.2 Object lifetimes

**Created once at startup (singletons, live until shutdown):** `DataSource`, Flyway, `EntityManagerFactory`, `TransactionManager`, four repository proxies, `PasswordEncoder`, `SecurityFilterChain`, `AuthServiceImpl` (proxy), `AuthController`, `GlobalExceptionHandler`, `DispatcherServlet`.

**Created per request (short-lived):** `HttpServletRequest/Response`, `RegisterRequest`, entity objects (`User`, `Account`, ...), the `RegisterResponse`, `BigDecimal` results, `ResponseEntity`. After the method returns they become unreachable and are *eligible* for garbage collection (freed later, not instantly).

**Consequence:** ten requests reuse the same controller and service objects, each with its own request/response data. So **never keep request-specific state in a field of a bean**.

## 7.3 Concept FAQ

**Q: Who calls `AuthController.register()` if my code doesn't?**
Spring MVC's HandlerAdapter, after DispatcherServlet and HandlerMapping matched the URL. Postman only sends HTTP.

**Q: When are beans created?**
At startup, stage 5, before any request. Singletons are created once and reused.

**Q: If beans exist at startup, why isn't `register()` called then?**
Creating an object (constructors, `@Bean` methods) and calling its business methods are separate events.

**Q: What does `@SpringBootApplication` do?**
Combines `@SpringBootConfiguration`, `@ComponentScan` (finds your beans) and `@EnableAutoConfiguration` (configures from the classpath).

**Q: How does Spring know to create a datasource, Tomcat, Jackson, etc.?**
Auto-configuration classes guarded by conditions (library on classpath, property set, no user-defined bean). Your dependencies decide what gets configured.

**Q: Why did my security rules replace Boot's defaults?**
`@ConditionalOnMissingBean`: Boot's default security backs off when you define your own `SecurityFilterChain`.

**Q: Where do `HttpSecurity` and `build()` fit?**
Startup: Spring injects an `HttpSecurity` builder into your `@Bean` method; you declare rules; `build()` produces one `SecurityFilterChain`, stored as a bean. Request time: that same chain's filters apply the stored rules to every request.

**Q: Does `requestMatchers("/api/auth/**")` check the URL when I click Send?**
No, it declares a rule at startup. The filters apply it per request.

**Q: Does `permitAll()` bypass Spring Security?**
No. The request still passes through the filter chain; the authorization decision is "allowed without login".

**Q: What is the difference between a servlet and a filter?**
A servlet handles a request (DispatcherServlet). A filter runs before/after it and can block the request (security).

**Q: Is a repository interface with no implementation class a problem?**
No. Spring Data JPA generates the implementation at startup and registers it as a bean.

**Q: How does `findByUserIdAndAccountType` become SQL?**
Spring Data parses the method name into a JPQL query; Hibernate translates JPQL into MySQL SQL; JDBC executes it.

**Q: Why do DTOs need getters/setters?**
Jackson fills request DTOs through setters and reads response DTOs through getters.

**Q: Why `ResponseEntity`?**
To control the HTTP status (201) and headers.

**Q: Why does `saveAndFlush` matter if the transaction commits at the end anyway?**
To surface constraint violations inside the `try` block and to get generated ids immediately. A flush is not a commit.

**Q: Why a custom UPDATE instead of read-modify-save for the SYSTEM balance?**
To make the balance check and subtraction a single atomic statement, preventing lost updates under concurrent registrations.

**Q: What is Hibernate Validator vs Hibernate ORM?**
Unrelated libraries from the same organisation: one validates input objects, the other maps objects to tables.

## 7.4 Glossary

| Term | Meaning |
|---|---|
| **Spring Core** | IoC container + DI foundation |
| **Spring MVC** | Web layer built on the Servlet API |
| **Spring Boot** | Auto-configuration, starters, embedded server on top of Spring |
| **Starter** | One dependency that brings a compatible bundle of libraries |
| **Maven / `pom.xml`** | Build tool / its configuration file listing dependencies and plugins |
| **Transitive dependency** | A dependency of your dependency, downloaded automatically |
| **Classpath** | Set of JARs and classes visible to the running app; drives auto-configuration |
| **IoC** | Framework creates and calls your objects |
| **Bean** | Object managed by Spring |
| **ApplicationContext** | Container holding bean definitions and instances |
| **DI (constructor injection)** | Spring supplies dependencies through the constructor |
| **Component scan** | Finding annotated classes under the main package |
| **Stereotype annotation** | `@Component`, `@Service`, `@RestController`, etc. |
| **Auto-configuration** | Conditional, classpath-driven bean setup |
| **Singleton** | One shared instance per application (default bean scope) |
| **Proxy** | A wrapper object that adds behaviour (e.g. transactions) around a bean |
| **Tomcat** | Embedded web/servlet container |
| **Servlet** | Java class that handles HTTP requests |
| **Filter** | Code that runs around the servlet; can block requests |
| **DispatcherServlet** | Spring MVC's front controller |
| **HandlerMapping / HandlerAdapter** | Find / invoke the controller method |
| **Jackson** | JSON library (serialization / deserialization) |
| **DTO** | Object carrying request or response data |
| **Bean Validation** | Annotation-based validation (`@NotBlank`, `@Email`, `@Size`) |
| **`HttpSecurity`** | Builder for security rules (prototype bean injected by Spring Security) |
| **`SecurityFilterChain`** | Finished, ordered set of security filters |
| **CSRF** | Cookie-abuse attack; not relevant for stateless token APIs |
| **BCrypt** | Salted one-way password hashing |
| **JPA / Hibernate / Spring Data JPA** | Specification / implementation / repository abstraction |
| **Entity** | Java class mapped to a table |
| **JPQL** | JPA's object-oriented query language (entity names, not table names) |
| **Derived query** | Query built from a method name like `findByUserId` |
| **Persistence context** | Hibernate's in-memory cache of entities in the current transaction |
| **Flush** | Send pending changes to the DB (not a commit) |
| **Transaction / commit / rollback** | Atomic group of DB operations / make permanent / undo |
| **HikariCP** | Connection pool |
| **Flyway / `flyway_schema_history`** | Schema migrations / table recording applied ones |
| **`ddl-auto=validate`** | Hibernate verifies schema vs entities; changes nothing |
| **Idempotency key** | Unique key preventing the same operation being applied twice |

## 7.5 Verifying the result in the database

After registering one user, run these in MySQL Workbench (adjust ids). They check the invariants the design promises:

```sql
-- 1. The user, and their two accounts
SELECT * FROM `User` ORDER BY user_id DESC LIMIT 1;
SELECT * FROM Account WHERE user_id = <new_user_id>;       -- BANK 5000.00, WALLET 0.00

-- 2. The opening-balance transaction
SELECT * FROM `Transaction` WHERE idempotency_key = 'OPENING-<new_user_id>';

-- 3. Its two ledger entries (one DEBIT on SYSTEM, one CREDIT on the BANK account)
SELECT * FROM LedgerEntry WHERE transaction_id = <transaction_id>;

-- 4. Ledger balance check: debit total must equal credit total
SELECT
  SUM(CASE WHEN entry_type = 'DEBIT'  THEN amount END) AS total_debit,
  SUM(CASE WHEN entry_type = 'CREDIT' THEN amount END) AS total_credit
FROM LedgerEntry;

-- 5. SYSTEM account dropped by exactly 5000 per registered user
SELECT account_id, balance FROM Account WHERE account_type = 'SYSTEM';
```

Failure cases to confirm: registering the same email again returns **409** and creates no new rows and no extra debit (rolled back); invalid email or short password returns **400** and touches nothing.

---

# Appendix A: Code references

## A.1 `AuthServiceImpl` (full)

```java
package com.aureus.aureus.service;

import com.aureus.aureus.dto.RegisterRequest;
import com.aureus.aureus.dto.RegisterResponse;

import com.aureus.aureus.entity.Account;
import com.aureus.aureus.entity.AccountType;
import com.aureus.aureus.entity.EntryType;
import com.aureus.aureus.entity.LedgerEntry;
import com.aureus.aureus.entity.Role;
import com.aureus.aureus.entity.Transaction;
import com.aureus.aureus.entity.TransactionStatus;
import com.aureus.aureus.entity.TransactionType;
import com.aureus.aureus.entity.User;
import com.aureus.aureus.entity.UserStatus;

import com.aureus.aureus.exception.DuplicateEmailException;

import com.aureus.aureus.repository.AccountRepository;
import com.aureus.aureus.repository.LedgerEntryRepository;
import com.aureus.aureus.repository.TransactionRepository;
import com.aureus.aureus.repository.UserRepository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

@Service
public class AuthServiceImpl implements AuthService {

    private static final BigDecimal OPENING_BALANCE =
            new BigDecimal("5000.00");

    private static final BigDecimal ZERO =
            new BigDecimal("0.00");

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthServiceImpl(
            UserRepository userRepository,
            AccountRepository accountRepository,
            TransactionRepository transactionRepository,
            LedgerEntryRepository ledgerEntryRepository,
            PasswordEncoder passwordEncoder) {

        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public RegisterResponse register(RegisterRequest request) {

        // STEP 1: Normalize email and reject existing users.
        String email = request.getEmail()
                .trim()
                .toLowerCase(Locale.ROOT);

        if (userRepository.existsByEmail(email)) {
            throw new DuplicateEmailException(
                    "Email is already registered"
            );
        }

        // STEP 2: Hash password and insert the user.
        String passwordHash =
                passwordEncoder.encode(request.getPassword());

        User user = new User(
                email,
                passwordHash,
                Role.USER,
                UserStatus.ACTIVE
        );

        try {
            // Flush now so MySQL checks uniqueness and generates userId.
            user = userRepository.saveAndFlush(user);

        } catch (DataIntegrityViolationException exception) {
            // Covers the race where concurrent requests insert
            // the same email after both passed existsByEmail().
            throw new DuplicateEmailException(
                    "Email is already registered",
                    exception
            );
        }

        Integer userId = user.getUserId();

        // STEP 3: Create the customer's BANK and WALLET accounts.
        Account bankAccount = accountRepository.saveAndFlush(
                new Account(
                        userId,
                        AccountType.BANK,
                        OPENING_BALANCE
                )
        );

        Account walletAccount = accountRepository.saveAndFlush(
                new Account(
                        userId,
                        AccountType.WALLET,
                        ZERO
                )
        );

        // STEP 4: Find the SYSTEM account and debit it safely.
        List<Account> systemAccounts =
                accountRepository.findByAccountType(AccountType.SYSTEM);

        // Our current schema does not enforce a single SYSTEM account
        // globally, so verify the expected seed-data invariant.
        if (systemAccounts.size() != 1) {
            throw new IllegalStateException(
                    "Expected exactly one SYSTEM account"
            );
        }

        Integer systemAccountId =
                systemAccounts.get(0).getAccountId();

        int rowsUpdated =
                accountRepository.debitIfSufficientBalance(
                        systemAccountId,
                        OPENING_BALANCE
                );

        if (rowsUpdated != 1) {
            throw new IllegalStateException(
                    "SYSTEM account has insufficient funds "
                            + "or could not be debited"
            );
        }

        // STEP 5: Record the opening-balance transaction.
        String idempotencyKey = "OPENING-" + userId;

        Transaction openingTransaction =
                new Transaction(
                        systemAccountId,
                        bankAccount.getAccountId(),
                        OPENING_BALANCE,
                        TransactionType.OPENING_BALANCE,
                        TransactionStatus.COMPLETED,
                        idempotencyKey,
                        null
                );

        openingTransaction =
                transactionRepository.saveAndFlush(openingTransaction);

        Integer transactionId =
                openingTransaction.getTransactionId();

        // STEP 6: Record the matching debit and credit entries.
        LedgerEntry debitEntry = new LedgerEntry(
                transactionId,
                systemAccountId,
                EntryType.DEBIT,
                OPENING_BALANCE
        );

        LedgerEntry creditEntry = new LedgerEntry(
                transactionId,
                bankAccount.getAccountId(),
                EntryType.CREDIT,
                OPENING_BALANCE
        );

        ledgerEntryRepository.saveAll(
                List.of(debitEntry, creditEntry)
        );

        // STEP 7: Return the registration response.
        return new RegisterResponse(
                userId,
                user.getEmail(),
                bankAccount.getBalance(),
                walletAccount.getBalance()
        );
    }
}
```

## A.2 `AccountRepository` (full)

```java
package com.aureus.aureus.repository;

import com.aureus.aureus.entity.Account;
import com.aureus.aureus.entity.AccountType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface AccountRepository
        extends JpaRepository<Account, Integer> {

    List<Account> findByUserId(Integer userId);

    Optional<Account> findByUserIdAndAccountType(
            Integer userId,
            AccountType accountType
    );

    List<Account> findByAccountType(AccountType accountType);

    @Modifying(
        flushAutomatically = true,
        clearAutomatically = true
    )
    @Query("""
        UPDATE Account a
        SET a.balance = a.balance - :amount
        WHERE a.accountId = :accountId
          AND a.balance >= :amount
        """)
    int debitIfSufficientBalance(
            @Param("accountId") Integer accountId,
            @Param("amount") BigDecimal amount
    );

}
```

## A.3 The other files referenced (as built earlier in Day 3)

`RegisterRequest`, `RegisterResponse`, `AuthController`, `SecurityConfig` and `application.properties` are shown in full in the relevant sections above (R7, R9, R4.1, 3.6). `RegisterResponse` carries `userId`, `email`, `bankBalance`, `walletBalance` with a constructor and getters.

---

**End of Day 3 report.** Registration works end to end. Next up: login with JWT, then Feature 3 (Add Money), the first feature to use ledger posting, locking and idempotency on every call.