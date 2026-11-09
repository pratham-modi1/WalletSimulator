# Aureus: Report 4
## Login and View My Accounts: what runs after what (the JWT security layer, at runtime)

**Endpoints covered:** `POST /api/auth/login` and `GET /api/accounts/me`
**Focus:** runtime order, i.e. the exact sequence of components a request passes through, with one example JWT traced through every layer, and what would break if each layer were missing.
**Why this matters:** "walk me through what happens when a request hits a protected endpoint" is one of the most common Spring Security interview questions. This report is that answer, with a real token.

---

# Table of contents

1. The cheat sheet: order of execution (read this first, revise from this)
2. The cast: every class involved and its job
3. Startup recap: what exists before the first request
4. The example JWT (a real, decodable token)
5. Runtime A: `POST /api/auth/login`
6. Runtime B: `GET /api/accounts/me` with the JWT travelling through every layer
7. Same token, different outcomes: failure scenarios
8. "What if this layer were missing?" master table
9. Interview questions (runtime-focused)
10. Limits, trade-offs and notes for Day 7 onward

---

# 1. The cheat sheet: order of execution

## 1.1 Startup (once)

```text
 1. JVM starts -> SpringApplication.run()
 2. Component scan finds JwtService (@Service), JsonAuthenticationEntryPoint, controllers, services
 3. JwtService constructor runs:  secret (Base64 text) -> keyBytes -> signingKey -> jwtParser
 4. PasswordEncoder bean created (BCrypt)
 5. SecurityConfig.securityFilterChain(...) runs ONCE:
       new JwtAuthFilter(jwtService, userRepository, entryPoint)
       csrf off, formLogin off, httpBasic off, STATELESS, entry point set,
       rules: /api/auth/** permitAll, everything else authenticated,
       JwtAuthFilter positioned BEFORE UsernamePasswordAuthenticationFilter
       http.build()  ->  SecurityFilterChain bean
 6. Spring Security registers its filter with Tomcat; Tomcat starts on 8080
```

## 1.2 Runtime: login (public endpoint)

```text
Postman -> Tomcat -> springSecurityFilterChain -> FilterChainProxy -> your SecurityFilterChain
  -> [early security filters]
  -> JwtAuthFilter            shouldNotFilter() = TRUE  -> skipped, chain continues
  -> [remaining filters]
  -> ExceptionTranslationFilter (waits, nothing thrown)
  -> AuthorizationFilter      /api/auth/** -> permitAll -> ALLOWED
  -> DispatcherServlet -> HandlerMapping -> Jackson (JSON -> LoginRequest) -> @Valid
  -> AuthController.login -> AuthServiceImpl.login
        normalise email -> findByEmail -> status ACTIVE? -> BCrypt matches?
        -> JwtService.generateToken(userId, role)
  -> LoginResponse -> Jackson (object -> JSON) -> back out -> Postman: 200 + token
```

## 1.3 Runtime: view my accounts (protected endpoint)

```text
Postman (Authorization: Bearer <JWT>) -> Tomcat -> springSecurityFilterChain -> FilterChainProxy
  -> [early security filters]           (SecurityContext starts EMPTY for this request)
  -> JwtAuthFilter                      shouldNotFilter() = FALSE -> doFilterInternal runs
        1 read Authorization header
        2 starts with "Bearer "?        (no -> pass on, unauthenticated)
        3 substring(7).trim()  -> raw token
        4 JwtService.extractClaims      signature verified + expiry checked
        5 userId = sub, role = claim
        6 userRepository.findById       user must exist
        7 status must be ACTIVE
        8 DB role must equal token role
        9 new AuthenticatedUser(...)
       10 authorities = [ROLE_USER]
       11 new UsernamePasswordAuthenticationToken(principal, null, authorities)
       12 SecurityContext.setAuthentication -> SecurityContextHolder.setContext
       13 filterChain.doFilter(...)     (any failure in 4-8: rejectRequest -> 401, STOP)
  -> [remaining filters, incl. AnonymousAuthenticationFilter: skips, already authenticated]
  -> ExceptionTranslationFilter         (wraps the next filter, waits for exceptions)
  -> AuthorizationFilter                anyRequest().authenticated()  -> YES -> ALLOWED
  -> DispatcherServlet -> HandlerMapping -> AccountController.getMyAccounts
        @AuthenticationPrincipal injects the AuthenticatedUser from the SecurityContext
  -> AccountService.getMyAccounts(principal.userId())
  -> AccountRepository.findByUserId -> MySQL
  -> MyAccountsResponse -> Jackson -> Postman: 200 + balances
```

**Three sentences to remember:**
1. `JwtAuthFilter` **establishes** authentication; `AuthorizationFilter` **checks** it. The first runs earlier.
2. `.authenticated()` never parses a JWT. It only asks "is there an authenticated Authentication in the SecurityContext?"
3. Login runs on a **password**; every later request runs on the **JWT**.

---

# 2. The cast: every class involved

| Class | Package | New or modified | Runs at startup | Runs at request time |
|---|---|---|---|---|
| `SecurityConfig` | config | Modified | Builds the chain once; creates `PasswordEncoder` | Never re-run; its product (the chain) is used |
| `JwtService` | service | New | Constructor builds key + parser | `generateToken` (login), `extractClaims` (protected requests) |
| `JwtAuthFilter` | security | New | Constructed inside `SecurityConfig` | Runs for every non-`/api/auth` request |
| `AuthenticatedUser` | security | New (record) | Nothing | Created per request by the filter; read by the controller |
| `JsonAuthenticationEntryPoint` | security | New | Bean created | Writes the JSON 401 when authentication fails |
| `AuthController` | controller | Modified (added `login`) | Bean | Handles `POST /api/auth/login` |
| `AuthService` / `AuthServiceImpl` | service | Modified (added `login`) | Bean | Verifies credentials, asks `JwtService` for a token |
| `AccountController` | controller | New | Bean | Handles `GET /api/accounts/me` |
| `AccountService` / `AccountServiceImpl` | service | New | Bean | Fetches the caller's accounts |
| `LoginRequest`, `LoginResponse`, `MyAccountsResponse` | dto | New | none | Per-request objects |
| `GlobalExceptionHandler` | exception | Modified (added `ResponseStatusException` handler) | Registered | Keeps 401 as 401 instead of 500 |

**Responsibility split in one line each**

- **Security layer** (filter, principal, entry point): decides *who you are*.
- **Controller:** HTTP in and out.
- **Service:** business logic.
- **Repository:** database.
- **Config:** assembles all of the above once.

---

# 3. Startup recap: what exists before the first request

Only what matters for the runtime story.

## 3.1 `JwtService` constructor (runs once, when the bean is created)

```text
@Value("${jwt.secret}")  ->  "YXVyZXVzLWRlbW8tc2VjcmV0LWtleS0zMmJ5dGVzISE="   (Base64 TEXT from config)
Decoders.BASE64.decode   ->  32 raw bytes
Keys.hmacShaKeyFor(...)  ->  signingKey   (a SecretKey object)
Jwts.parser().verifyWith(signingKey).build()  ->  jwtParser
expirationMs  = 3600000  (1 hour, default)
```

Why built once: key and parser configuration never change, so every request just reuses them.

**Variables to keep apart:** `secret` (Base64 text) is not `keyBytes` (raw bytes), which is not `signingKey` (the key object), which is not the *signature* (output of using the key on a token). Base64 is encoding, not encryption.

## 3.2 `SecurityConfig.securityFilterChain` (runs once)

```java
JwtAuthFilter jwtAuthFilter = new JwtAuthFilter(jwtService, userRepository, authenticationEntryPoint);

http
  .csrf(csrf -> csrf.disable())
  .formLogin(form -> form.disable())
  .httpBasic(basic -> basic.disable())
  .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
  .exceptionHandling(e -> e.authenticationEntryPoint(authenticationEntryPoint))
  .authorizeHttpRequests(auth -> auth
        .requestMatchers("/api/auth/**").permitAll()
        .anyRequest().authenticated())
  .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

return http.build();
```

None of this runs per request. It **declares** rules and **positions** the filter; `build()` freezes them into the chain.

| Line | What it declares | If missing |
|---|---|---|
| `csrf.disable()` | No CSRF filter | POST requests from Postman would be rejected (403) for lacking a CSRF token |
| `formLogin.disable()` | No built-in login page/filter | Spring would expose its own form-login machinery alongside your API login |
| `httpBasic.disable()` | No `Authorization: Basic` handling | A second, unintended way to authenticate would exist |
| `STATELESS` | No HTTP session; context not saved between requests | Spring could create a session cookie and keep the user logged in outside your JWT design |
| `authenticationEntryPoint(...)` | JSON 401 for unauthenticated access | Default behaviour would not give your consistent JSON error |
| `permitAll()` on `/api/auth/**` | Register and login are public | Nobody could ever get a token (chicken and egg) |
| `anyRequest().authenticated()` | Default deny | Every unlisted endpoint would be open to the world |
| `addFilterBefore(jwtAuthFilter, ...)` | Your filter runs early | JWTs would never be read; every protected request would be rejected |

**`UsernamePasswordAuthenticationFilter.class`** is only a **position marker** ("put my filter just before this one in the filter order"). It does not run your login, and with form login disabled it is not doing any work. `.class` is Java's way of referring to the class itself, not creating an object.

## 3.3 What the security filter chain looks like (simplified)

```text
Tomcat
  -> DelegatingFilterProxy ("springSecurityFilterChain")
     -> FilterChainProxy
        -> YOUR SecurityFilterChain, roughly:
             SecurityContextHolderFilter      creates an empty context for this request, clears it at the end
             HeaderWriterFilter               adds security response headers
             LogoutFilter                     (default logout handling)
             >>> JwtAuthFilter                YOUR filter, inserted here
             RequestCacheAwareFilter / SecurityContextHolderAwareRequestFilter
             AnonymousAuthenticationFilter    if still unauthenticated, marks the request "anonymous"
             SessionManagementFilter
             ExceptionTranslationFilter       catches security exceptions from below and turns them into responses
             AuthorizationFilter              applies permitAll / authenticated rules
        -> DispatcherServlet
```

This is an illustrative order. The exact list depends on version and settings; the relative order (yours before authorization) is what matters.

---

# 4. The example JWT (a real, decodable token)

To make the runtime concrete, here is an actual HS256 token generated with a **demo secret** (do not ever use it for anything real).

**Demo secret (as stored in config, Base64 text):** `YXVyZXVzLWRlbW8tc2VjcmV0LWtleS0zMmJ5dGVzISE=`
(decodes to the 32-byte text `aureus-demo-secret-key-32bytes!!`)

**Scenario:** user 101 (`USER`) logged in at **10:30:00 UTC on 7 Nov 2026**; token lifetime is 1 hour.

## 4.1 The token

```text
eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMDEiLCJyb2xlIjoiVVNFUiIsImlhdCI6MTc5NDA0NzQwMCwiZXhwIjoxNzk0MDUxMDAwfQ.VbYHfYTtoUc0hBMFMTbxJTpG8wK-qTCVJqtR_JYQw5A
```

Three parts separated by dots: `HEADER . PAYLOAD . SIGNATURE`.

## 4.2 The parts, decoded

| Part | Encoded (Base64URL) | Decoded |
|---|---|---|
| Header | `eyJhbGciOiJIUzI1NiJ9` | `{"alg":"HS256"}` (some libraries also add `"typ":"JWT"`) |
| Payload | `eyJzdWIiOiIxMDEiLCJyb2xlIjoiVVNFUiIsImlhdCI6MTc5NDA0NzQwMCwiZXhwIjoxNzk0MDUxMDAwfQ` | `{"sub":"101","role":"USER","iat":1794047400,"exp":1794051000}` |
| Signature | `VbYHfYTtoUc0hBMFMTbxJTpG8wK-qTCVJqtR_JYQw5A` | 32 bytes of HMAC output (not human-readable) |

**The payload claims**

| Claim | Value | Meaning |
|---|---|---|
| `sub` | `"101"` | Subject: the user's id |
| `role` | `"USER"` | Custom claim: role at the time of login |
| `iat` | 1794047400 | Issued at = 2026-11-07 10:30:00 UTC |
| `exp` | 1794051000 | Expires at = 2026-11-07 11:30:00 UTC |

## 4.3 Where the signature comes from

```text
signature = Base64URL( HMAC-SHA256( key = signingKey,
                                    message = "<encoded header>.<encoded payload>" ) )
```

- `signWith(signingKey, HS256)` computes this **at login**.
- `parseSignedClaims(token)` recomputes it **at each protected request** and compares.
- Only someone holding the secret key can produce a matching signature.

## 4.4 Three facts that drive the whole security design

1. **The payload is readable by anyone.** Paste the middle part into any Base64 decoder and you see the user id and role. A JWT here is *signed*, not *encrypted*. Never put secrets in it.
2. **The signature makes it tamper-evident, not secret.** Change one character of the payload and the signature no longer matches (shown in section 7).
3. **Whoever holds the token is "logged in"** until it expires (a bearer token). That is why the filter also re-checks the database.

---

# 5. Runtime A: `POST /api/auth/login`

**Request**
```text
POST /api/auth/login
Content-Type: application/json

{"email":"Pratham@Gmail.com ","password":"mypassword123"}
```
(Illustrative credentials. Note the capital P and trailing space: the service normalises them.)

**There is no JWT on this request. Its job is to obtain one.**

## Step-by-step

| # | What runs | Who triggers it | What happens | Why it exists / if it were missing |
|---|---|---|---|---|
| 1 | Postman sends HTTP over TCP to port 8080 | You | Request line, headers, JSON body as text | |
| 2 | **Tomcat** | Network | Parses the HTTP, builds `HttpServletRequest` / `HttpServletResponse`, assigns a worker thread | Without a servlet container there is nothing to receive HTTP |
| 3 | **`springSecurityFilterChain` → `FilterChainProxy`** | Tomcat's filter chain | Picks your `SecurityFilterChain` and starts running its filters | Security must run before any controller |
| 4 | **`SecurityContextHolderFilter`** | Chain | Creates an empty `SecurityContext` for this request | Each request starts unauthenticated |
| 5 | **`JwtAuthFilter`**: `shouldNotFilter()` | `OncePerRequestFilter` | Path is `/api/auth/login`, starts with `/api/auth/`, returns **true**, so `doFilterInternal` is **skipped** and the chain continues | See "stale token" note below |
| 6 | **`ExceptionTranslationFilter`** | Chain | Wraps the rest; nothing is thrown | |
| 7 | **`AuthorizationFilter`** | Chain | Rule `/api/auth/**` → `permitAll()`; **allowed** without authentication | Without `permitAll`, login would return 401 and nobody could get a token |
| 8 | **`DispatcherServlet`** | Tomcat/Spring | Front controller for Spring MVC | |
| 9 | **`HandlerMapping`** | DispatcherServlet | `POST /api/auth/login` → `AuthController.login` | |
| 10 | **Jackson** (`@RequestBody`) | HandlerAdapter | JSON → `LoginRequest` object | Without it you'd parse JSON by hand |
| 11 | **Validation** (`@Valid`, if on the DTO) | HandlerAdapter | Email format / not blank | Bad input rejected before touching the database |
| 12 | **`AuthController.login(request)`** | HandlerAdapter | Calls `authService.login(request)` | Controller only handles HTTP |
| 13 | **`AuthServiceImpl.login`**: normalise | Your code | `"Pratham@Gmail.com "` → `"pratham@gmail.com"` (`trim()` + `toLowerCase(Locale.ROOT)`) | Without it, capitalisation or a stray space makes a valid user "not found" |
| 14 | **`userRepository.findByEmail`** | Your code | SELECT user by email | |
| 15 | **Status check** | Your code | `userStatus` must be `ACTIVE` | Without it, deleted users could log in and receive tokens |
| 16 | **`passwordEncoder.matches(raw, storedHash)`** | Your code | BCrypt compares the raw password with the stored hash | Without it, anyone knowing an email gets a token |
| 17 | **`jwtService.generateToken(userId, role)`** | Your code | Builds and signs the token (below) | |
| 18 | **`LoginResponse`** built | Your code | token, `"Bearer"`, `expiresIn = 3600` | Tells the client how to send it and for how long |
| 19 | **Jackson** (response) | Spring MVC | Object → JSON | |
| 20 | Response travels back out | Tomcat | **200 OK** + JSON | |

If steps 14 to 16 fail (unknown email, inactive, wrong password), a `ResponseStatusException` with 401 is thrown, and `GlobalExceptionHandler` turns it into a JSON 401 instead of a generic 500. **No token is created.** For safety the message should be identical for "unknown email" and "wrong password" so attackers can't tell which emails exist.

## Step 17 in detail: `generateToken(101, USER)`

| Builder call | Effect on the token |
|---|---|
| `Date now = new Date()` | 10:30:00 UTC |
| `expiry = now + expirationMs` | 11:30:00 UTC |
| `.subject("101")` | payload `sub` |
| `.claim("role", "USER")` | payload `role` |
| `.issuedAt(now)` | payload `iat` |
| `.expiration(expiry)` | payload `exp` |
| `.signWith(signingKey, HS256)` | computes the signature over header + payload |
| `.compact()` | joins `header.payload.signature` into one string |

## What Postman receives

```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMDEiLCJyb2xlIjoiVVNFUiIsImlhdCI6MTc5NDA0NzQwMCwiZXhwIjoxNzk0MDUxMDAwfQ.VbYHfYTtoUc0hBMFMTbxJTpG8wK-qTCVJqtR_JYQw5A",
  "tokenType": "Bearer",
  "expiresIn": 3600
}
```

## Why `shouldNotFilter` matters (the non-obvious one)

If the filter did *not* skip `/api/auth/**`: a login request with **no** header would pass through harmlessly. But if a client sends a **stale or expired token** while logging in again (very common: the old token is still stored in the app), the filter would parse it, fail, and **reject the login with 401**, blocking the very request that would give the user a fresh token. Skipping login and register paths makes them immune to bad tokens.

---

# 6. Runtime B: `GET /api/accounts/me` with the JWT travelling through every layer

**Request** (sent at **10:45:00 UTC**, 15 minutes after login)
```text
GET /api/accounts/me
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMDEiLCJyb2xlIjoiVVNFUiIsImlhdCI6MTc5NDA0NzQwMCwiZXhwIjoxNzk0MDUxMDAwfQ.VbYHfYTtoUc0hBMFMTbxJTpG8wK-qTCVJqtR_JYQw5A
```

The token from section 4 now makes the journey. For every step: **what the token looks like at that moment**, **what the code does**, **why it exists**, and **what happens if it's absent**.

## Stage 0: before your filter

Steps 1 to 4 are identical to login: Postman, Tomcat (builds request/response objects), `FilterChainProxy` selects your chain, `SecurityContextHolderFilter` creates an **empty** context. At this moment the request is **anonymous**: nobody knows who 101 is yet.

## Stage 1: `shouldNotFilter()`

| | |
|---|---|
| Input | path `/api/accounts/me` |
| Logic | `path.equals("/api/auth") \|\| path.startsWith("/api/auth/")` → `false \|\| false` → **false** |
| Result | Do **not** skip. `doFilterInternal` runs |
| Why | Protected endpoints must go through JWT processing |
| If absent | The filter would run on login too (see stale-token issue) |

## Stage 2: `doFilterInternal`: the JWT's journey

### Step 2.1: read the header

```java
String header = request.getHeader(HttpHeaders.AUTHORIZATION);
```
Token state: still wrapped: `"Bearer eyJhbGci...Qw5A"`.
If absent: nothing tells the server who is calling; this is the only way the credential arrives.

### Step 2.2: check the scheme

```java
if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
    filterChain.doFilter(request, response);
    return;
}
```
- `regionMatches(true, 0, "Bearer ", 0, 7)`: compare 7 characters (`Bearer` = 6 + space = 1), ignoring case.
- Here it matches, so we continue.
- Missing or wrong scheme: the filter **passes the request on unauthenticated** (it doesn't reject itself). The authorization stage will deny it later (section 7).
- If absent: `substring(7)` could throw or take garbage from a `Basic ...` header.

### Step 2.3: strip the prefix

```java
String token = header.substring(7).trim();
```
Token state: now the **bare JWT**: `eyJhbGciOiJIUzI1NiJ9.eyJzdWIi...Qw5A`.
`isEmpty()` check right after: `Authorization: Bearer ` with nothing after it gives `""`, which is rejected through `rejectRequest`.

### Step 2.4: verify signature and expiry: `jwtService.extractClaims(token)`

This is the cryptographic heart. `jwtParser.parseSignedClaims(token)` does, in order:

| Sub-step | With our token |
|---|---|
| Split on dots | header `eyJhbGci...`, payload `eyJzdWIi...`, signature `VbYHfYTt...` |
| Read header | `alg = HS256` |
| Recompute signature | `HMAC-SHA256(signingKey, "eyJhbGciOiJIUzI1NiJ9.eyJzdWIi...MDB9")` |
| Compare | recomputed `VbYHfYTtoUc0hBMFMTbxJTpG8wK-qTCVJqtR_JYQw5A` **equals** the token's signature ✔ |
| Check expiry | `exp` = 11:30:00; now = 10:45:00; **not expired** ✔ |
| Return payload | `Claims{ sub="101", role="USER", iat=..., exp=... }` |

- Any failure throws a `JwtException` (bad signature, malformed, expired), which the `catch` turns into a 401.
- **If this step were missing** (i.e. trusting the payload without verifying the signature): anyone could write `{"sub":"1","role":"ADMIN"}`, Base64-encode it and be admin. The signature is the *only* thing preventing forgery.

### Step 2.5: read the claims

```java
Integer userId   = Integer.valueOf(claims.getSubject());     // "101" -> 101
String tokenRole = claims.get("role", String.class);          // "USER"
```
Token state: reduced to two plain values: `userId = 101`, `tokenRole = "USER"`. A malformed `sub` (e.g. `"abc"`) throws `NumberFormatException` (a subclass of `IllegalArgumentException`), which the catch block handles.

### Step 2.6: load the user from the database

```java
User user = userRepository.findById(userId)
        .orElseThrow(() -> new BadCredentialsException("Invalid token"));
```
- A valid signature proves **we issued this token once**. It does not prove the user still exists.
- **If absent:** a deleted user's still-valid token (up to 1 hour) would keep working.

### Step 2.7: status check

```java
if (user.getUserStatus() != UserStatus.ACTIVE) throw new BadCredentialsException("User is inactive");
```
- Gives you an instant "kill switch" per user, without needing token revocation.
- **If absent:** soft-deleted users keep access until the token expires.

### Step 2.8: role consistency check

```java
if (user.getRole() == null || !user.getRole().name().equals(tokenRole)) throw ...
```
- The token role (`USER`) is a **snapshot from login time**. The database holds the **current** role.
- Mismatch (e.g. promoted or demoted since login) → reject, forcing a re-login.
- **If absent:** a demoted admin's old token would still carry `ADMIN` authority; the token, not the database, would decide privileges.

At this point the token has done its job. It has been **verified, decoded and cross-checked**. From here on, the request doesn't carry "a token" any more; it carries an **identity**.

### Step 2.9: build the principal

```java
AuthenticatedUser principal = new AuthenticatedUser(user.getUserId(), user.getEmail(), user.getRole());
// AuthenticatedUser[userId=101, email=pratham@gmail.com, role=USER]
```
- A small immutable record: only the fields the app needs, not the whole JPA `User` entity (no password hash flowing around).

### Step 2.10: build the authorities

```java
List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))   // [ROLE_USER]
```
- Spring's `hasRole("ADMIN")` checks the authority `ROLE_ADMIN`. The `ROLE_` prefix is the convention.
- Built from the **database** role (verified equal to the token's).

### Step 2.11: build the `Authentication`

```java
new UsernamePasswordAuthenticationToken(principal, null, authorities)
```
- Three-argument constructor = an **already authenticated** token. No password check happens here; we already verified the JWT.
- Credentials `null`: we deliberately don't keep any secret.
- `setDetails(new WebAuthenticationDetailsSource().buildDetails(request))` attaches request metadata (remote address, etc.).

### Step 2.12: store it where Spring Security looks

```java
SecurityContext context = SecurityContextHolder.createEmptyContext();
context.setAuthentication(authentication);
SecurityContextHolder.setContext(context);
```

```text
SecurityContextHolder (thread-local: this request's thread)
   -> SecurityContext
        -> Authentication (authenticated = true)
             principal   = AuthenticatedUser(101, pratham@gmail.com, USER)
             credentials = null
             authorities = [ROLE_USER]
```
- **If absent:** the JWT would be valid but Spring Security would still believe the request is anonymous, so `.authenticated()` fails.
- With `STATELESS`, this context is **not saved** to any session. It exists for this request/thread and is cleared when the request ends.

### Step 2.13: hand over

```java
filterChain.doFilter(request, response);
```
Means "continue with the remaining filters", **not** "request approved". The next stage can still deny it.

## Stage 3: remaining filters and `AuthorizationFilter`

| Filter | What it does for our request |
|---|---|
| `AnonymousAuthenticationFilter` | Sees an authenticated `Authentication` already present → does nothing (it only adds "anonymous" when none exists) |
| `ExceptionTranslationFilter` | Wraps the next filter; if a security exception is thrown below, converts it into a 401 (via the entry point) or 403 |
| **`AuthorizationFilter`** | Rule lookup: `/api/accounts/me` doesn't match `/api/auth/**`, so `anyRequest().authenticated()` applies. Is the Authentication present **and** authenticated? **Yes** → access granted |

**This is where the order matters.** `JwtAuthFilter` ran **earlier** and put the identity in the context. If the order were reversed, `AuthorizationFilter` would run first, see no identity, and reject every request before the JWT is ever read.

`.authenticated()` has never seen the JWT. It only checks the result of what your filter built.

## Stage 4: Spring MVC and your code

| # | What runs | What happens |
|---|---|---|
| 1 | `DispatcherServlet` | Receives the allowed request |
| 2 | `HandlerMapping` | `GET /api/accounts/me` → `AccountController.getMyAccounts` |
| 3 | `HandlerAdapter` + `@AuthenticationPrincipal` argument resolver | Reads the current `Authentication` from the `SecurityContext` and injects its **principal** as the method parameter: `AuthenticatedUser(101, ...)` |
| 4 | `AccountController` | Calls `accountService.getMyAccounts(principal.userId())` |
| 5 | `AccountServiceImpl` | `accountRepository.findByUserId(101)` → BANK and WALLET rows → `MyAccountsResponse` |
| 6 | Jackson | Object → JSON |
| 7 | Response out | **200 OK** |

```json
{ "bankBalance": 2000.00, "walletBalance": 3000.00 }
```
(Illustrative values.)

**Why the controller takes the id from the principal, never from the client:** if the endpoint were `GET /api/accounts?userId=102`, any logged-in user could read anyone's balances. Because the id comes from the verified JWT, user 101 can only ever ask for user 101's accounts. This pattern will protect all the money endpoints too.

**If `@AuthenticationPrincipal` didn't exist:** every controller would have to re-read the header, re-verify the token, and re-load the user. Security would be copy-pasted into every endpoint.

---

# 7. Same token, different outcomes: failure scenarios

All using the example token or a modified copy. Times in UTC on 7 Nov 2026.

| # | Scenario | The input | Which line catches it | Result |
|---|---|---|---|---|
| A | **Valid** | Token as issued, sent at 10:45 | Passes every check | 200 + balances |
| B | **Tampered payload** (role changed to ADMIN, old signature kept) | see 7.1 | `parseSignedClaims`: recomputed signature ≠ token signature → `JwtException` | `rejectRequest` → **401**, filter stops |
| C | **Expired** | Same token sent at 11:45 (`exp` = 11:30) | `parseSignedClaims`: expiry check → `ExpiredJwtException` (a `JwtException`) | **401** |
| D | **No header** | no `Authorization` | Step 2.2: passes through unauthenticated; `AnonymousAuthenticationFilter` marks it anonymous; `AuthorizationFilter` denies; `ExceptionTranslationFilter` sees an anonymous caller and calls the entry point | **401** (JSON) |
| E | **Wrong scheme** | `Authorization: Basic abc123` | Step 2.2: `regionMatches` false → same path as D | **401** |
| F | **Empty token** | `Authorization: Bearer ` | Step 2.3: `token.isEmpty()` → `rejectRequest` | **401** |
| G | **Garbage token** | `Bearer abc.def.ghi` | `parseSignedClaims`: malformed/undecodable → `JwtException` | **401** |
| H | **Signed with a different secret** | Valid-looking token from another key | Signature mismatch (7.2) | **401** |
| I | **Valid token, user now INACTIVE/deleted** | Token OK, DB status changed | Step 2.6/2.7 → `BadCredentialsException` | **401** |
| J | **Valid token, role changed in DB** | token `USER`, DB `ADMIN` | Step 2.8 → `BadCredentialsException` | **401** (user must log in again) |
| K | **Stale token on login** | Expired token + `POST /api/auth/login` | `shouldNotFilter` skips the JWT filter → login proceeds normally | **200** with a fresh token |

**Two different 401 paths (worth remembering):**
- **Bad token present** (B, C, F, G, H, I, J): `JwtAuthFilter` itself catches it, calls `rejectRequest` → clears the context → `authenticationEntryPoint.commence(...)` → JSON 401 → **`return`** (the chain stops; the controller never runs).
- **No token** (D, E): `JwtAuthFilter` lets it through; **authorization** rejects it later; `ExceptionTranslationFilter` routes it to the same entry point.

Both end in the same JSON response, which is why the client sees one consistent 401.

## 7.1 Scenario B in numbers: why tampering fails

An attacker decodes the payload (readable!), changes `"role":"USER"` to `"ADMIN"`, re-encodes it, and keeps the old signature.

```text
Tampered payload (Base64URL):
eyJzdWIiOiIxMDEiLCJyb2xlIjoiQURNSU4iLCJpYXQiOjE3OTQwNDc0MDAsImV4cCI6MTc5NDA1MTAwMH0

Token sent:
eyJhbGciOiJIUzI1NiJ9.<tampered payload>.VbYHfYTtoUc0hBMFMTbxJTpG8wK-qTCVJqtR_JYQw5A   (old signature)

What the server recomputes over header + tampered payload:
WwmMqlb_7R-QDv-Dq5m34HizMKm1VR2owZBFPRZ7LsU

Token's signature : VbYHfYTtoUc0hBMFMTbxJTpG8wK-qTCVJqtR_JYQw5A
Recomputed        : WwmMqlb_7R-QDv-Dq5m34HizMKm1VR2owZBFPRZ7LsU
Equal?            : NO  ->  JwtException  ->  401
```

To forge a valid signature for the new payload the attacker would need the **secret key**, which never leaves the server. (That's also why a weak or leaked secret destroys the whole scheme.)

## 7.2 Scenario H in numbers: wrong secret

The same header and payload signed with a different key produce a different signature:

```text
Signed with our key        : VbYHfYTtoUc0hBMFMTbxJTpG8wK-qTCVJqtR_JYQw5A
Signed with another secret : PtmRl1am6d5PALkYJtSOX8pS_uXh1Nj-VOV1b1-lzyo
```

The server recomputes with *its* key, gets the first value, sees the second, and rejects.

---

# 8. "What if this layer were missing?" master table

| Layer or line | Its job | If it were missing |
|---|---|---|
| `permitAll()` on `/api/auth/**` | Register and login reachable without a token | Login returns 401; no one can ever get a token |
| `anyRequest().authenticated()` | Default deny | Endpoints you forget to list are open to everyone |
| `JwtAuthFilter` entirely | Turn a token into an identity | Every protected request rejected (valid tokens ignored) |
| `shouldNotFilter` | Skip auth paths | Stale or invalid tokens block login/registration |
| `addFilterBefore(... )` position | Run before authorization | Authorization rejects requests before the JWT is read |
| Bearer prefix check | Detect the right scheme | Crashes or mis-parses on `Basic`/empty headers |
| `extractClaims` signature check | Prove the token is ours | Anyone can forge any user or role |
| Expiry check | Limit the life of a stolen token | A leaked token works forever |
| DB lookup by id | Prove the user still exists | Deleted users' tokens keep working until expiry |
| Status check | Instant per-user kill switch | Deactivated users keep access |
| Role-match check | Prevent stale privileges | Demoted users keep old privileges |
| `AuthenticatedUser` principal | Small identity object | You'd pass the whole `User` entity (with the password hash) around |
| `SecurityContext` storing | Make identity visible downstream | Valid JWT, yet the request is still "anonymous" |
| `.authenticated()` rule | Enforce that identity exists | Protection is only advisory |
| `@AuthenticationPrincipal` | Give controllers the identity | Security logic duplicated in every controller |
| `rejectRequest` + `return` | Stop the chain on a bad token | Request would continue to the controller after failing |
| `JsonAuthenticationEntryPoint` | Consistent JSON 401 | Default non-JSON/inconsistent errors |
| `STATELESS` | No server-side session | Sessions and cookies appear, contradicting the JWT design |
| `BCrypt.matches` at login | Verify the password | Anyone with an email can log in |
| Status check at login | No tokens for inactive users | Deactivated users can still obtain tokens |
| `signWith(...)` at login | Tamper-evidence | Tokens are plain editable JSON |
| `GlobalExceptionHandler` for `ResponseStatusException` | Keep status codes | A deliberate 401 turns into a generic 500 |

---

# 9. Interview questions (runtime-focused)

**Q1. Walk me through what happens when a request with a JWT reaches a protected endpoint.**
Tomcat receives it and passes it to Spring Security's filter chain. My `JwtAuthFilter` runs early: it reads the `Authorization` header, strips `Bearer `, verifies the signature and expiry with the secret key, reads the user id and role, loads the user from the database, checks status and role, then builds an `AuthenticatedUser`, wraps it in an `Authentication`, and stores it in the `SecurityContext`. Later `AuthorizationFilter` checks `.authenticated()`; since an identity exists, the request reaches `DispatcherServlet` and the controller, where `@AuthenticationPrincipal` gives me the user.

**Q2. What does `.authenticated()` actually check?**
Only that the `SecurityContext` holds an authenticated `Authentication`. It never reads the JWT; my filter does that.

**Q3. Why does your filter run before `AuthorizationFilter`?**
Because authentication must be established before authorization is evaluated. I used `addFilterBefore`, which fixes the position at startup.

**Q4. What does `filterChain.doFilter()` mean?**
"Continue with the remaining filters." It isn't approval; later filters can still reject.

**Q5. What's the difference between the signing key and the signature?**
The signing key is the secret used for HMAC. The signature is the HMAC output attached to each token.

**Q6. Is a JWT encrypted?**
No, HS256 JWTs are signed. The payload is Base64URL-encoded and readable by anyone. Don't store secrets in it.

**Q7. How does the server detect a tampered token?**
It recomputes the HMAC over header and payload with its key and compares it with the token's signature. Any change makes them differ.

**Q8. Why query the database on every request if the JWT already has the user id?**
The JWT proves we issued the token earlier, not that the user is still active or has the same role. The lookup gives instant deactivation and role checks, at the cost of one query per request.

**Q9. Why is the login endpoint excluded from the JWT filter?**
There's no token yet, and a stale token must not block a fresh login.

**Q10. What happens for a request with no token versus an invalid token?**
No token: the filter passes it on unauthenticated, then authorization denies and the entry point returns 401. Invalid token: the filter rejects it itself via `rejectRequest` and stops the chain. Both give the same JSON 401.

**Q11. Why disable CSRF and use `STATELESS`?**
The API authenticates with an explicit `Authorization` header, not cookies the browser sends automatically, so CSRF isn't the threat and no server session is needed. Each request re-proves identity through the JWT.

**Q12. How does the controller know who's calling?**
`@AuthenticationPrincipal` reads the principal from the `SecurityContext` that my filter populated. The controller never trusts a user id from the request.

**Q13. What's the difference between `User` and `AuthenticatedUser`?**
`User` is the JPA entity mapped to the table. `AuthenticatedUser` is a small immutable record used as the security principal.

**Q14. HS256 vs RS256?**
HS256 uses one shared secret to sign and verify. RS256 signs with a private key and verifies with a public key, which suits systems where many services must verify tokens without being able to create them.

---

# 10. Limits, trade-offs and notes for Day 7 onward

**Trade-offs of this design**

- **No revocation before expiry, except via the database checks.** A leaked token works for up to 1 hour unless the user is deactivated or their role changes. Short lifetimes limit the damage.
- **One database query per protected request.** That's the price of instant deactivation; fine for this project.
- **A role change forces re-login** (the token's role no longer matches). That's intentional.
- **The secret is the crown jewel.** It must be long (at least 32 bytes), random, private, stable across restarts, and kept out of Git (environment variable). Rotating it invalidates all existing tokens.

**Notes from your testing**

- Verified: register two users, duplicate email rejected, valid login, invalid login (401), no token (401), garbage token (401), user A and user B each see only their own balances (isolation).
- **Not verified:** an old token belonging to a deleted or inactive user (scenario I). Treat it as untested, not passed. It's a five-minute test: log in, set `user_status = 'DELETED'` in MySQL, call `/api/accounts/me` with the old token, and expect 401.
- Tokens pasted into chats or notes during testing should be treated as exposed. Rotate `jwt.secret` before trusting any real data.

**What carries into Day 7 (money endpoints)**

- Every wallet endpoint is automatically protected by `anyRequest().authenticated()`; no new security config.
- Every controller will take `@AuthenticationPrincipal AuthenticatedUser principal` and use `principal.userId()`, never an id from the body.
- The idempotency key will be stored as `U<userId>-<clientKey>`, built from that same principal id, so keys are per-user.
- Admin-only endpoints (Day 10 reversals) will use the `ROLE_ADMIN` authority this filter already builds, and a non-admin will get a **403** (authenticated but not allowed), as distinct from the **401** here (not authenticated).

**End of Report 4.**