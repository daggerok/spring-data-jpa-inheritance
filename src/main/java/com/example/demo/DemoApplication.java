package com.example.demo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.*;
import lombok.experimental.Accessors;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.PersistenceConstructor;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.*;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.TimeZone;
import java.util.UUID;

/** Domain events */

interface Identity<ID> {
  ID getAggregateId();
}

interface DomainEvent<ID> extends Identity<ID> {}

interface HistoricallyTrackable {
  LocalDateTime getOccurredAt();
}

@Data
@Entity
@Table(name = "domain_events")
@Setter(AccessLevel.PROTECTED)
@DiscriminatorColumn(name = "event_type")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
abstract class BaseDomainEvent implements DomainEvent<UUID>, HistoricallyTrackable {

  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "domain_event_sequence_number")
  @SequenceGenerator(name = "domain_event_sequence_number", initialValue = 1, allocationSize = 1)
  protected Long id;

  @Column(updatable = false, nullable = false)
  protected UUID aggregateId;

  @CreatedDate // @LastModifiedDate
  @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:SSS")
  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
  protected LocalDateTime occurredAt;
}

interface DomainEventRepository extends JpaRepository<BaseDomainEvent, Long> {}

@Data
@Entity
@Getter
@ToString(callSuper = true)
@Setter(AccessLevel.PROTECTED)
@EqualsAndHashCode(callSuper = true)
@DiscriminatorValue("VisitorRegisteredEvent")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class VisitorRegisteredEvent extends BaseDomainEvent {

  private String name;

  @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:SSS")
  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
  private LocalDateTime expireAt;

  public static VisitorRegisteredEvent of(UUID aggregateId, String name, LocalDateTime expireAt) {
    return new VisitorRegisteredEvent(null, aggregateId, null, name, expireAt);
  }

  @PersistenceConstructor//@com.fasterxml.jackson.annotation.JsonCreator
  VisitorRegisteredEvent(/*@JsonProperty("id")*/ Long id,
                         /*@JsonProperty("aggregateId")*/ UUID aggregateId,
                         /*@JsonProperty("occurredAt")*/ LocalDateTime occurredAt,
                         /*@JsonProperty("name")*/ String name,
                         /*@JsonProperty("expireAt")*/ LocalDateTime expireAt) {
    super(id, aggregateId, occurredAt);
    this.name = name;
    this.expireAt = expireAt;
  }
}

@Data
@Entity
@Getter
@ToString(callSuper = true)
@Setter(AccessLevel.PROTECTED)
@EqualsAndHashCode(callSuper = true)
@DiscriminatorValue("PassCardDeliveredEvent")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class PassCardDeliveredEvent extends BaseDomainEvent {

  public static PassCardDeliveredEvent of(UUID aggregateId) {
    return new PassCardDeliveredEvent(null, aggregateId, null);
  }

  @PersistenceConstructor
  PassCardDeliveredEvent(Long id, UUID aggregateId, LocalDateTime occurredAt) {
    super(id, aggregateId, occurredAt);
  }
}

@Data
@Entity
@Getter
@ToString(callSuper = true)
@Setter(AccessLevel.PROTECTED)
@EqualsAndHashCode(callSuper = true)
@DiscriminatorValue("EnteredTheDoorEvent")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class EnteredTheDoorEvent extends BaseDomainEvent {

  private String doorId;

  public static EnteredTheDoorEvent of(UUID aggregateId, String doorId) {
    return new EnteredTheDoorEvent(null, aggregateId, null, doorId);
  }

  @PersistenceConstructor
  EnteredTheDoorEvent(Long id, UUID aggregateId, LocalDateTime occurredAt, String doorId) {
    super(id, aggregateId, occurredAt);
    this.doorId = doorId;
  }
}

/** Aggregate State Snapshots */

interface State<ID> extends Identity<ID> {}

interface MutableState<ID> extends State<ID> {
  MutableState<ID> mutate(DomainEvent<ID> domainEvent);
}

@Data
@Log4j2
@MappedSuperclass
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
class VisitorState implements MutableState<UUID>, HistoricallyTrackable {

  private UUID aggregateId;
  private String name;

  @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:SSS")
  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
  private LocalDateTime expireAt;

  @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:SSS")
  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
  private LocalDateTime deliveredAt;

  private String lastDoorId;

  @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:SSS")
  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
  private LocalDateTime lastDoorEnteredAt;

  @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:SSS")
  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
  private LocalDateTime occurredAt;

  @Override
  public VisitorState mutate(DomainEvent<UUID> domainEvent) {
    var anEvent = Optional.ofNullable(domainEvent).orElseThrow();
    if (anEvent instanceof VisitorRegisteredEvent) return onVisitorRegisteredEvent((VisitorRegisteredEvent) anEvent);
    if (anEvent instanceof PassCardDeliveredEvent) return onPassCardDeliveredEvent((PassCardDeliveredEvent) anEvent);
    if (anEvent instanceof EnteredTheDoorEvent) return onEnteredTheDoorEvent((EnteredTheDoorEvent) anEvent);
    return onUnsupportedDomainEvent(anEvent);
    // return Match(anEvent).of(
    //     Case($(instanceOf(VisitorRegisteredEvent.class)), this::onVisitorRegisteredEvent),
    //     Case($(instanceOf(PassCardDeliveredEvent.class)), this::onPassCardDeliveredEvent),
    //     Case($(instanceOf(EnteredTheDoorEvent.class)), this::onEnteredTheDoorEvent),
    //     Case($(), this::onUnsupportedDomainEvent)
    // );
  }

  private VisitorState onVisitorRegisteredEvent(VisitorRegisteredEvent event) {
    return this.setAggregateId(event.getAggregateId())
               .setName(event.getName())
               .setExpireAt(event.getExpireAt())
               .setOccurredAt(event.getOccurredAt());
  }

  private VisitorState onPassCardDeliveredEvent(PassCardDeliveredEvent event) {
    return this.setDeliveredAt(event.getOccurredAt())
               .setOccurredAt(event.getOccurredAt());
  }

  private VisitorState onEnteredTheDoorEvent(EnteredTheDoorEvent event) {
    return this.setLastDoorId(event.getDoorId())
               .setLastDoorEnteredAt(event.getOccurredAt())
               .setOccurredAt(event.getOccurredAt());
  }

  private VisitorState onUnsupportedDomainEvent(DomainEvent<UUID> event) {
    log.warn("Fallback: {}", event);
    return this;
  }
}

@Data
@Entity
@Table(name = "snapshots")
@ToString(callSuper = true)
@Setter(AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
class VisitorStateSnapshot extends VisitorState {

  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "state_sequence")
  @SequenceGenerator(name = "state_sequence", initialValue = 1, allocationSize = 1)
  protected Long id;

  @Version
  protected Long version;

  @PersistenceConstructor
  public VisitorStateSnapshot(Long id, UUID aggregateId, String name, LocalDateTime expireAt,
                              LocalDateTime deliveredAt, String lastDoorId, LocalDateTime lastDoorEnteredAt,
                              LocalDateTime occurredAt, Long version) {
    super(aggregateId, name, expireAt, deliveredAt, lastDoorId, lastDoorEnteredAt, occurredAt);
    this.id = id;
    this.version = version;
  }

  public VisitorStateSnapshot patchWith(VisitorState state) {

    Optional.ofNullable(state.getAggregateId())
            .filter(that -> !that.equals(this.getAggregateId()))
            .ifPresent(this::setAggregateId);

    Optional.ofNullable(state.getName())
            .filter(that -> !that.equals(this.getName()))
            .ifPresent(this::setName);

    Optional.ofNullable(state.getExpireAt())
            .filter(that -> !that.equals(this.getExpireAt()))
            .ifPresent(this::setExpireAt);

    Optional.ofNullable(state.getDeliveredAt())
            .filter(that -> !that.equals(this.getDeliveredAt()))
            .ifPresent(this::setDeliveredAt);

    Optional.ofNullable(state.getLastDoorId())
            .filter(that -> !that.equals(this.getLastDoorId()))
            .ifPresent(this::setLastDoorId);

    Optional.ofNullable(state.getLastDoorEnteredAt())
            .filter(that -> !that.equals(this.getLastDoorEnteredAt()))
            .ifPresent(this::setLastDoorEnteredAt);

    Optional.ofNullable(state.getOccurredAt())
            .filter(that -> !that.equals(this.getOccurredAt()))
            .ifPresent(this::setOccurredAt);

    return this;
  }

  public static VisitorStateSnapshot noop(UUID aggregateId) {
    return new VisitorStateSnapshot(0L, aggregateId, null, null, null, null, null, null, null);
  }

  /* NOTE: this method is package-private only for testing */static VisitorStateSnapshot from(VisitorState state) {
    return new VisitorStateSnapshot(null, state.getAggregateId(), state.getName(), state.getExpireAt(),
                                    state.getDeliveredAt(), state.getLastDoorId(), state.getLastDoorEnteredAt(),
                                    state.getOccurredAt(), null);
  }
}

interface VisitorStateSnapshotRepository extends JpaRepository<VisitorStateSnapshot, Long> {

  Optional<VisitorStateSnapshot> findFirstByAggregateId(UUID aggregateId);

  @Transactional
  default Optional<VisitorStateSnapshot> update(VisitorState state) {
    var aState = Objects.requireNonNull(state, "state may not be null");
    var aggregateId = Objects.requireNonNull(aState.getAggregateId(), "aggregate ID may not be null");
    var patched = findFirstByAggregateId(aggregateId).orElse(VisitorStateSnapshot.noop(aggregateId))
                                                     .patchWith(state);
    var updated = save(patched);
    return Optional.of(updated);
  }
}

@SpringBootApplication
@EnableJpaRepositories
public class DemoApplication {

  public static void main(String[] args) {
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    SpringApplication.run(DemoApplication.class, args);
  }
}
