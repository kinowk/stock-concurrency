# 재고 시스템으로 알아보는 동시성 이슈 해결 방법

동시성 환경에서 발생하는 **재고 차감(Stock Decrease)** 시의 대표적인 문제들을 실제 코드로 풀어보는 프로젝트입니다.  
동일한 재고에 대해 다수의 요청이 동시에 들어올 때 발생하는 **Race Condition**을 방지하기 위해  
5가지 서로 다른 동시성 제어 방식을 구현하고 비교합니다.

---

## 목차

1. [프로젝트 개요](#프로젝트-개요)
2. [기술 스택](#기술-스택)
3. [프로젝트 구조](#프로젝트-구조)
4. [핵심 도메인 모델](#핵심-도메인-모델)
5. [동시성 이슈 시나리오](#동시성-이슈-시나리오)
6. [동시성 해결 방법 5가지](#동시성-해결-방법-5가지)
   - [1. 비관적 락 (Pessimistic Lock)](#1-비관적-락-pessimistic-lock)
   - [2. 낙관적 락 (Optimistic Lock)](#2-낙관적-락-optimistic-lock)
   - [3. 데이터베이스 명시적 락 (Named Lock)](#3-데이터베이스-명시적-락-named-lock)
   - [4. Redis Lettuce 락](#4-redis-lettuce-락)
   - [5. Redis Redisson 락 (분산 락)](#5-redis-redisson-락-분산-락)
7. [각 방식별 장단점 비교](#각-방식별-장단점-비교)
8. [실행 방법](#실행-방법)
9. [테스트](#테스트)

---

## 프로젝트 개요

전자상거래 시스템에서 **재고 차감**은 가장 전형적인 동시성 문제가 발생하는 지점입니다.  
예를 들어, 재고가 100개일 때 100명의 사용자가 동시에 각각 1개씩 주문하면 정상적으로는 재고가 0이 되어야 하지만,  
동시성 제어가 없다면 **재고가 마이너스(-)가 되거나, 실제보다 더 많이 차감되는 문제**가 발생합니다.

이 프로젝트는 이러한 문제를 해결하기 위한 5가지 동시성 제어 전략을 실제 구현 코드로 보여줍니다.

---

## 기술 스택

| 분류 | 기술 |
|------|------|
| Language | Java 17 |
| Framework | Spring Boot 4.0.5 (Spring 6) |
| Build Tool | Gradle 9.4.0 |
| ORM | Spring Data JPA (Hibernate) |
| Database | MySQL 8.x |
| Cache / Distributed Lock | Redis (Lettuce, Redisson 4.3.0) |
| Transaction Retry | Spring Retry 1.3.4 |
| Utility | Lombok 1.18.44 |
| Test | JUnit 5, Spring Boot Test |

---

## 프로젝트 구조

```
stock/
├── build.gradle
├── settings.gradle
├── gradlew / gradlew.bat
├── gradle/
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties
├── src/
│   ├── main/
│   │   ├── java/com/example/stock/
│   │   │   ├── StockApplication.java          # Spring Boot 메인 클래스
│   │   │   ├── domain/
│   │   │   │   └── Stock.java                # 재고 엔티티 (핵심 도메인)
│   │   │   ├── repository/
│   │   │   │   ├── StockRepository.java      # 레포지토리 인터페이스
│   │   │   │   ├── StockJpaRepository.java   # JPA 레포지토리 (락 쿼리 포함)
│   │   │   │   ├── LockRepository.java       # DB Named Lock 인터페이스
│   │   │   │   └── RedisLockRepository.java  # Redis 락 헬퍼
│   │   │   ├── service/
│   │   │   │   ├── StockService.java         # 기본 재고 차감 서비스
│   │   │   │   ├── OptimisticLockStockService.java   # 낙관적 락 서비스
│   │   │   │   └── PessimisticLockStockService.java # 비관적 락 서비스
│   │   │   └── facade/
│   │   │       ├── NamedLockStockFacade.java       # Named Lock 퍼사드
│   │   │       ├── OptimisticLockStockFacade.java   # 낙관적 락 + Retry 퍼사드
│   │   │       ├── LettuceLockStockFacade.java      # Lettuce Redis 락 퍼사드
│   │   │       └── RedissonLockStockFacade.java     # Redisson 분산 락 퍼사드
│   │   └── resources/
│   │       └── application.yml                     # 설정 파일
│   └── test/
│       └── java/com/example/stock/
│           ├── StockApplicationTests.java
│           ├── service/
│           │   └── StockServiceTest.java
│           └── facade/
│               ├── NamedLockStockFacadeTest.java
│               ├── OptimisticLockStockFacadeTest.java
│               ├── LettuceLockStockFacadeTest.java
│               └── RedissonLockStockFacadeTest.java
└── HELP.md
```

---

## 핵심 도메인 모델

### Stock Entity

```java
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Stock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long productId;
    private Long quantity;

    @Version
    private Long version;  // 낙관적 락을 위한 버전 필드

    public Stock(Long productId, Long quantity) { ... }

    public void decrease(Long quantity) {
        if (this.quantity < quantity) {
            throw new RuntimeException("재고는 0개 미만이 될 수 없습니다.");
        }
        this.quantity -= quantity;
    }
}
```

- `quantity`: 현재 재고 수량
- `version`: JPA `@Version` 어노테이션을 통한 낙관적 락 지원
- `decrease()`: 재고 차감 메서드 (음수 검증 포함)

---

## 동시성 이슈 시나리오

### 문제 상황

```java
// 동시성 제어가 없는 기본 StockService
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void decrease(Long id, Long quantity) {
    Stock stock = stockRepository.findById(id).orElseThrow();
    stock.decrease(quantity);  // READ → MODIFY → WRITE
    stockRepository.save(stock);
}
```

**100개의 재고에 대해 100개의 요청이 동시에 들어오는 경우:**

| 시점 | Thread A | Thread B | DB 재고 |
|------|----------|----------|---------|
| T1 | 재고 조회 (100) | | 100 |
| T2 | | 재고 조회 (100) | 100 |
| T3 | 100 - 1 = 99 저장 | | 99 |
| T4 | | 100 - 1 = 99 저장 | 99 ❌ |

→ **실제로는 2개가 차감되어야 하지만 1개만 차감되는 문제 발생**

---

## 동시성 해결 방법 5가지

### 1. 비관적 락 (Pessimistic Lock)

> 데이터를 조회할 때부터 **배타적 락(Exclusive Lock)**을 걸어 다른 트랜잭션의 접근을 차단합니다.

**구현: `PessimisticLockStockService`**

```java
@Transactional
public void decrease(Long id, Long quantity) {
    Stock stock = stockRepository.findByIdForUpdate(id).orElseThrow();
    stock.decrease(quantity);
    stockRepository.save(stock);
}
```

**JPA Repository (`StockJpaRepository`):**

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)  // SELECT ... FOR UPDATE
Optional<Stock> findByIdForUpdate(Long id);
```

| 장점 | 단점 |
|------|------|
| 구현이 간단하고 안전함 | 락 대기로 인한 성능 저하 가능 |
| 데드락 발생 시 DB가 자동 롤백 | 연결(Connection)을 계속 점유함 |
| | 성능이 중요하고 충돌이 잦을 때 적합 |

---

### 2. 낙관적 락 (Optimistic Lock)

> 락을 걸지 않고 **버전(Version) 필드**를 이용해 충돌을 감지합니다.  
> 충돌 발생 시 예외가 던져지며, 이를 **재시도(Retry)**하는 방식으로 해결합니다.

**구현: `OptimisticLockStockService`**

```java
@Transactional
public void decrease(Long id, Long quantity) {
    Stock stock = stockRepository.findByIdWithOptimisticLock(id).orElseThrow();
    stock.decrease(quantity);
    stockRepository.save(stock);
}
```

**JPA Repository:**

```java
@Lock(LockModeType.OPTIMISTIC)
Optional<Stock> findByIdWithOptimisticLock(Long id);
```

**Facade에서 Spring Retry 적용 (무한 재시도):**

```java
@Component
public class OptimisticLockStockFacade {

    private final OptimisticLockStockService stockService;

    @Retryable(
        include = ObjectOptimisticLockingFailureException.class,
        maxAttempts = Integer.MAX_VALUE,  // 충돌 시 무한 재시도
        backoff = @Backoff(delay = 50)    // 재시도 간격 50ms
    )
    public void decrease(Long id, Long quantity) {
        stockService.decrease(id, quantity);
    }
}
```

| 장점 | 단점 |
|------|------|
| 락을 걸지 않아 성능이 좋음 | 충돌 시 재시도 비용 발생 |
| 별도의 DB/Redis 설정 불필요 | 재시도 로직이 필요함 |
| 읽기가 많은 환경에 적합 | 충돌이 잦하면 성능 저하 |

---

### 3. 데이터베이스 명시적 락 (Named Lock)

> MySQL의 **`GET_LOCK()` / `RELEASE_LOCK()`** 함수를 사용하여  
> 애플리케이션 레벨에서 명시적으로 락을 획득하고 해제합니다.

**구현: `NamedLockStockFacade`**

```java
@Transactional
public void decrease(Long id, Long quantity) {
    try {
        lockRepository.getLock(id.toString());  // GET_LOCK()
        stockService.decrease(id, quantity);
    } finally {
        lockRepository.releaseLock(id.toString());  // RELEASE_LOCK()
    }
}
```

**LockRepository (Native Query):**

```java
public interface LockRepository {
    @Query(value = "SELECT GET_LOCK(:key, 3000)", nativeQuery = true)
    int getLock(String key);

    @Query(value = "SELECT RELEASE_LOCK(:key)", nativeQuery = true)
    int releaseLock(String key);
}
```

| 장점 | 단점 |
|------|------|
| 비관적 락보다 가볍고 유연함 | 트랜잭션 범위 외부에서 락 관리 필요 |
| 분산 락 구현이 간단함 | 별도의 DB 커넥션 관리 주의 필요 |
| MySQL 전용 솔루션 | DB 특정 함수 사용으로 이식성 낮음 |

---

### 4. Redis Lettuce 락

> Redis의 **`SET key value NX EX seconds`** 명령어를 활용해  
> 단일 스레드 기반의 분산 락을 구현합니다.

**RedisLockRepository (Lettuce 방식):**

```java
@Repository
public class RedisLockRepository {

    private final RedisTemplate<String, String> redisTemplate;

    public Boolean lock(Long key) {
        return redisTemplate.opsForValue()
                .setIfAbsent(generateLockKey(key), "lock", Duration.ofMillis(3_000));
    }

    public Boolean unlock(Long key) {
        return redisTemplate.delete(generateLockKey(key));
    }
}
```

**구현: `LettuceLockStockFacade` (Spin Lock 방식)**

```java
@Component
public class LettuceLockStockFacade {

    private final StockService stockService;
    private final RedisLockRepository redisLockRepository;

    public void decrease(Long id, Long quantity) throws InterruptedException {
        // 락 획득 시까지 계속 재시도 (Spin Lock)
        while (!redisLockRepository.lock(id)) {
            Thread.sleep(100);  // 100ms 대기 후 재시도
        }

        try {
            stockService.decrease(id, quantity);
        } finally {
            redisLockRepository.unlock(id);
        }
    }
}
```

| 장점 | 단점 |
|------|------|
| 구현이 간단함 | 락 만료 시간(TTL) 설정에 주의 필요 |
| Redis 기반의 가벼운 분산 락 | Spin Lock 방식으로 CPU 자원 낭비 가능 |
| | 락 해제 시 누군가가 획득한 락을 다른 스레드가 해제할 위험 |

---

### 5. Redis Redisson 락 (분산 락)

> Redis 클라이언트인 **Redisson**의 `RLock`을 사용하여  
> **Pub/Sub** 기반의 효율적인 분산 락을 구현합니다.

**구현: `RedissonLockStockFacade`**

```java
public void decrease(Long id, Long quantity) {
    RLock lock = redissonClient.getLock(id.toString());
    try {
        boolean available = lock.tryLock(10, 1, TimeUnit.SECONDS);
        if (!available) {
            System.out.println("lock 획득 실패");
            return;
        }
        stockService.decrease(id, quantity);
    } catch (InterruptedException e) {
        throw new RuntimeException(e);
    } finally {
        lock.unlock();
    }
}
```

| 장점 | 단점 |
|------|------|
| Pub/Sub 기반으로 효율적 (다른 스레드 대기 안 함) | Redisson 라이브러리 추가 의존성 |
| 락 갱신(extension) 기능 지원 | 설정이 다소 복잡 |
| **운영 환경에서 가장 권장되는 방식** | Redis 서버가 SPOF가 될 수 있음 |
| 다양한 락 옵션 제공 (Fair Lock, ReadWrite Lock 등) | |

---

## 각 방식별 장단점 비교

| 구분 | 비관적 락 | 낙관적 락 | Named Lock | Lettuce 락 | Redisson 락 |
|------|----------|----------|------------|-----------|------------|
| **구현 난이도** | ⭐ 쉬움 | ⭐⭐ 보통 | ⭐⭐ 보통 | ⭐⭐ 보통 | ⭐⭐⭐ 다소 복잡 |
| **성능** | 낮음 (락 대기) | 높음 (충돌 적을 때) | 보통 | 보통 | 높음 |
| **추천 환경** | 충돌이 잦함 | 읽기가 많음 | MySQL 환경 | 간단한 분산락 | **운영 환경 (추천)** |
| **추가 의존성** | 없음 | 없음 | 없음 | spring-data-redis | redisson-spring-boot-starter |
| **분산 환경** | ❌ | ❌ | ⚠️ (DB 공용 시) | ✅ | ✅ |

> **💡 운영 환경 추천:** Redisson 분산 락  
> 이유: Pub/Sub 기반 효율적 대기, 락 갱신, 다양한 락 패턴 지원

---

## 실행 방법

### 1. 사전 준비

- **Java 17** 이상 설치
- **MySQL 8.x** 실행 (포트 3306)
  - 데이터베이스 생성: `stock_example`
  - 사용자: `root`, 비밀번호: `root`
- **Redis** 실행 (포트 6379)

### 2. 설정 확인

`src/main/resources/application.yml`

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: create
    show-sql: true
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://127.0.0.1:3306/stock_example
    username: root
    password: root
    hikari:
      maximum-pool-size: 40
```

### 3. 애플리케이션 실행

```bash
./gradlew bootRun
```

### 4. 데이터베이스 초기화

애플리케이션 실행 시 `ddl-auto: create` 설정으로 인해 테이블이 자동 생성됩니다.  
테스트 전 수동으로 `stock` 테이블에 초기 데이터를 넣어주세요:

```sql
INSERT INTO stock (product_id, quantity, version) VALUES (1, 100, 0);
```

---

## 테스트

각 동시성 제어 방식에 대해 **100개의 동시 요청**을 보내는 테스트가 구현되어 있습니다.

### 테스트 실행

```bash
./gradlew test
```

### 테스트 구조

| 테스트 클래스 | 검증 내용 |
|-------------|----------|
| `StockServiceTest` | 기본 방식 (동시성 문제 발생 확인) |
| `PessimisticLockStockServiceTest` | 비관적 락 동작 확인 |
| `OptimisticLockStockFacadeTest` | 낙관적 락 + Retry 동작 확인 |
| `NamedLockStockFacadeTest` | Named Lock 동작 확인 |
| `LettuceLockStockFacadeTest` | Lettuce 락 동작 확인 |
| `RedissonLockStockFacadeTest` | Redisson 락 동작 확인 |

### 테스트 시나리오 (예시)

```java
@Test
void concurrency_test() throws InterruptedException {
    int threadCount = 100;
    ExecutorService executorService = Executors.newFixedThreadPool(32);
    CountDownLatch latch = new CountDownLatch(threadCount);

    for (int i = 0; i < threadCount; i++) {
        executorService.submit(() -> {
            try {
                facade.decrease(1L, 1L);
            } finally {
                latch.countDown();
            }
        });
    }

    latch.await();

    Stock stock = stockRepository.findById(1L).orElseThrow();
    assertEquals(0, stock.getQuantity());  // 100개 모두 정상 차감 확인
}
```

---

## 참고 자료

- [MySQL GET_LOCK 문서](https://dev.mysql.com/doc/refman/8.0/en/locking-functions.html)
- [Spring Data JPA Locking](https://docs.spring.io/spring-data/jpa/reference/jpa/locking.html)
- [Redisson 문서](https://github.com/redisson/redisson)
- [Spring Retry](https://github.com/spring-projects/spring-retry)

---

> 본 프로젝트는 인프런 강의 **"재고 시스템으로 알아보는 동시성 이슈 해결 방법"**을 바탕으로 작성되었습니다.
