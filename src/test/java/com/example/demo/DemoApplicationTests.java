package com.example.demo;

import lombok.AllArgsConstructor;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@DisplayName("JPA tests")
@AllArgsConstructor(onConstructor_ = @Autowired)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class DemoApplicationTests {

  DomainEventRepository repository;
  VisitorStateSnapshotRepository snapshotRepository;

  @BeforeEach
  void setUp() {
    repository.deleteAllInBatch();
    snapshotRepository.deleteAllInBatch();
  }

  @Test
  void should_verify_inherited_domain_events() {
    // given
    var aggregateId = UUID.randomUUID();
    // when
    repository.save(VisitorRegisteredEvent.of(aggregateId, "test", LocalDateTime.now()));
    repository.save(PassCardDeliveredEvent.of(aggregateId));
    repository.save(EnteredTheDoorEvent.of(aggregateId, "IN-1"));
    repository.save(EnteredTheDoorEvent.of(aggregateId, "IN-2"));
    repository.save(EnteredTheDoorEvent.of(aggregateId, "OUT-2"));
    repository.save(EnteredTheDoorEvent.of(aggregateId, "OUT-1"));
    // then
    var domainEvents = repository.findAll();
    assertThat(domainEvents).isNotNull()
                            .isNotEmpty()
                            .matches(events -> events.size() == 6);
  }

  @Test
  void should_verify_snapshots() {
    // given
    var aggregateId = UUID.randomUUID();
    var state = new VisitorState().setAggregateId(aggregateId)
                                  .setName(" a test")
                                  .setExpireAt(LocalDateTime.now().plus(1, ChronoUnit.DAYS))
                                  .setDeliveredAt(LocalDateTime.now().minus(1, ChronoUnit.HOURS))
                                  .setLastDoorId("OUT-2")
                                  .setLastDoorEnteredAt(LocalDateTime.now().minus(1, ChronoUnit.MINUTES))
                                  .setOccurredAt(LocalDateTime.now());
    var snapshot = VisitorStateSnapshot.from(state);
    // when
    snapshotRepository.save(snapshot);
    // then
    var result = snapshotRepository.findAll();
    // and
    assertThat(result).isNotNull()
                      .isNotEmpty()
                      .hasSize(1);
    // and
    var updated = snapshotRepository.findFirstByAggregateId(aggregateId)
                                    .map(s -> s.patchWith(s.setLastDoorId("OUT-1")
                                                           .setLastDoorEnteredAt(LocalDateTime.now())))
                                    .map(snapshotRepository::save);
    assertThat(updated).isNotNull()
                       .isNotEmpty();
    var updatedSnapshot = updated.get();
    assertThat(updatedSnapshot).isNotNull()
                               .matches(visitorStateSnapshot -> visitorStateSnapshot.getVersion() == 1);
  }

  @Test
  void should_verify_search_by_aggregate_id() {
    // given
    var aggregateId = UUID.randomUUID();
    var state = new VisitorState().setAggregateId(aggregateId);
    var stateSnapshot = VisitorStateSnapshot.from(state);
    // when
    snapshotRepository.save(stateSnapshot);
    // then
    var optional = snapshotRepository.findFirstByAggregateId(aggregateId);
    // and
    assertThat(optional).isNotNull()
                        .isNotEmpty();
    var snapshot = optional.get();
    assertThat(snapshot).isNotNull();
  }

  @Test
  void should_update_snapshot() {
    // given initial
    var aggregateId = UUID.randomUUID();
    var initialState = new VisitorState().setAggregateId(aggregateId);
    var initialSnapshot = snapshotRepository.update(initialState);
    assertThat(initialSnapshot).isNotNull()
                               .isNotEmpty()
                               .matches(op -> op.get().getVersion() == 0);
    // when update
    var updatedState = initialState.setDeliveredAt(LocalDateTime.now());
    var updatedSnapshot = snapshotRepository.update(updatedState);
    assertThat(updatedSnapshot).isNotNull()
                               .isNotEmpty()
                               .matches(op -> op.get().getVersion() == 1);
    // then finally
    var enterOnce = updatedState.setLastDoorId("IN-1")
                                .setOccurredAt(LocalDateTime.now());
    var enterTwice = enterOnce.setLastDoorId("IN-2")
                              .setLastDoorEnteredAt(LocalDateTime.now());
    var finalSnapshot = snapshotRepository.update(enterTwice);
    assertThat(finalSnapshot).isNotNull()
                             .isNotEmpty()
                             .matches(op -> op.get().getVersion() == 2)
                             .matches(op -> op.get().getLastDoorId().equals("IN-2"));
  }
}
