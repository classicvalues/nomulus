// Copyright 2017 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.flows.poll;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.createHistoryEntryForEppResource;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.newDomainBase;
import static google.registry.testing.DatabaseHelper.persistActiveContact;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableMap;
import google.registry.flows.FlowTestCase;
import google.registry.flows.poll.PollAckFlow.InvalidMessageIdException;
import google.registry.flows.poll.PollAckFlow.MessageDoesNotExistException;
import google.registry.flows.poll.PollAckFlow.MissingMessageIdException;
import google.registry.flows.poll.PollAckFlow.NotAuthorizedToAckMessageException;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.poll.PollMessage;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.ReplayExtension;
import google.registry.testing.SetClockExtension;
import google.registry.testing.TestOfyAndSql;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link PollAckFlow}. */
@DualDatabaseTest
class PollAckFlowTest extends FlowTestCase<PollAckFlow> {

  @Order(value = Order.DEFAULT - 3)
  @RegisterExtension
  final SetClockExtension setClockExtension = new SetClockExtension(clock, "2011-01-02T01:01:01Z");

  @Order(value = Order.DEFAULT - 2)
  @RegisterExtension
  final ReplayExtension replayExtension = ReplayExtension.createWithDoubleReplay(clock);

  /** This is the message id being sent in the ACK request. */
  private static final long MESSAGE_ID = 3;

  private DomainBase domain;
  private ContactResource contact;

  @BeforeEach
  void setUp() {
    setEppInput("poll_ack.xml", ImmutableMap.of("MSGID", "1-3-EXAMPLE-4-3-2011"));
    setRegistrarIdForFlow("NewRegistrar");
    createTld("example");
    contact = persistActiveContact("jd1234");
    domain = persistResource(newDomainBase("test.example", contact));
  }

  private void persistOneTimePollMessage(long messageId) {
    persistResource(
        new PollMessage.OneTime.Builder()
            .setId(messageId)
            .setRegistrarId(getRegistrarIdForFlow())
            .setEventTime(clock.nowUtc().minusDays(1))
            .setMsg("Some poll message.")
            .setParent(createHistoryEntryForEppResource(domain))
            .build());
  }

  private void persistAutorenewPollMessage(DateTime eventTime, DateTime endTime) {
    persistResource(
        new PollMessage.Autorenew.Builder()
            .setId(MESSAGE_ID)
            .setRegistrarId(getRegistrarIdForFlow())
            .setEventTime(eventTime)
            .setAutorenewEndTime(endTime)
            .setMsg("Domain was auto-renewed.")
            .setTargetId("example.com")
            .setParent(createHistoryEntryForEppResource(domain))
            .build());
  }

  @TestOfyAndSql
  void testDryRun() throws Exception {
    persistOneTimePollMessage(MESSAGE_ID);
    dryRunFlowAssertResponse(loadFile("poll_ack_response_empty.xml"));
  }

  @TestOfyAndSql
  void testSuccess_contactPollMessage() throws Exception {
    setEppInput("poll_ack.xml", ImmutableMap.of("MSGID", "2-2-ROID-4-3-2011"));
    persistResource(
        new PollMessage.OneTime.Builder()
            .setId(MESSAGE_ID)
            .setRegistrarId(getRegistrarIdForFlow())
            .setEventTime(clock.nowUtc().minusDays(1))
            .setMsg("Some poll message.")
            .setParent(createHistoryEntryForEppResource(contact))
            .build());
    assertTransactionalFlow(true);
    runFlowAssertResponse(loadFile("poll_ack_response_empty.xml"));
  }

  @TestOfyAndSql
  void testFailure_contactPollMessage_withIncorrectYearField() throws Exception {
    setEppInput("poll_ack.xml", ImmutableMap.of("MSGID", "2-2-ROID-4-3-1999"));
    persistResource(
        new PollMessage.OneTime.Builder()
            .setId(MESSAGE_ID)
            .setRegistrarId(getRegistrarIdForFlow())
            .setEventTime(clock.nowUtc().minusDays(1))
            .setMsg("Some poll message.")
            .setParent(createHistoryEntryForEppResource(contact))
            .build());
    assertTransactionalFlow(true);
    assertThrows(MessageDoesNotExistException.class, this::runFlow);
  }

  @TestOfyAndSql
  void testSuccess_messageOnContactResource() throws Exception {
    persistOneTimePollMessage(MESSAGE_ID);
    assertTransactionalFlow(true);
    runFlowAssertResponse(loadFile("poll_ack_response_empty.xml"));
  }

  @TestOfyAndSql
  void testSuccess_recentActiveAutorenew() throws Exception {
    setEppInput("poll_ack.xml", ImmutableMap.of("MSGID", "1-3-EXAMPLE-4-3-2010"));
    persistAutorenewPollMessage(clock.nowUtc().minusMonths(6), END_OF_TIME);
    assertTransactionalFlow(true);
    runFlowAssertResponse(loadFile("poll_ack_response_empty.xml"));
  }

  @TestOfyAndSql
  void testSuccess_oldActiveAutorenew() throws Exception {
    setEppInput("poll_ack.xml", ImmutableMap.of("MSGID", "1-3-EXAMPLE-4-3-2009"));
    persistAutorenewPollMessage(clock.nowUtc().minusYears(2), END_OF_TIME);
    // Create three other messages to be queued for retrieval to get our count right, since the poll
    // ack response wants there to be 4 messages in the queue when the ack comes back.
    for (int i = 1; i < 4; i++) {
      persistOneTimePollMessage(MESSAGE_ID + i);
    }
    assertTransactionalFlow(true);
    runFlowAssertResponse(
        loadFile(
            "poll_ack_response.xml",
            ImmutableMap.of("MSGID", "1-3-EXAMPLE-4-3-2009", "COUNT", "4")));
  }

  @TestOfyAndSql
  void testSuccess_oldInactiveAutorenew() throws Exception {
    setEppInput("poll_ack.xml", ImmutableMap.of("MSGID", "1-3-EXAMPLE-4-3-2010"));
    persistAutorenewPollMessage(clock.nowUtc().minusMonths(6), clock.nowUtc());
    assertTransactionalFlow(true);
    runFlowAssertResponse(loadFile("poll_ack_response_empty.xml"));
  }

  @TestOfyAndSql
  void testSuccess_moreMessages() throws Exception {
    // Create five messages to be queued for retrieval, one of which will be acked.
    for (int i = 0; i < 5; i++) {
      persistOneTimePollMessage(MESSAGE_ID + i);
    }
    assertTransactionalFlow(true);
    runFlowAssertResponse(
        loadFile(
            "poll_ack_response.xml",
            ImmutableMap.of("MSGID", "1-3-EXAMPLE-4-3-2011", "COUNT", "4")));
  }

  @TestOfyAndSql
  void testFailure_noSuchMessage() throws Exception {
    assertTransactionalFlow(true);
    Exception e = assertThrows(MessageDoesNotExistException.class, this::runFlow);
    assertThat(e).hasMessageThat().contains(String.format("(1-3-EXAMPLE-4-%d-2011)", MESSAGE_ID));
  }

  @TestOfyAndSql
  void testFailure_invalidId_tooFewComponents() throws Exception {
    setEppInput("poll_ack.xml", ImmutableMap.of("MSGID", "1-2-3"));
    assertTransactionalFlow(true);
    assertThrows(InvalidMessageIdException.class, this::runFlow);
  }

  @TestOfyAndSql
  void testFailure_invalidId_tooManyComponents() throws Exception {
    setEppInput("poll_ack.xml", ImmutableMap.of("MSGID", "2-2-ROID-4-3-1999-2007"));
    assertTransactionalFlow(true);
    assertThrows(InvalidMessageIdException.class, this::runFlow);
  }

  @TestOfyAndSql
  void testFailure_contactPollMessage_withMissingYearField() throws Exception {
    setEppInput("poll_ack.xml", ImmutableMap.of("MSGID", "2-2-ROID-4-3"));
    persistResource(
        new PollMessage.OneTime.Builder()
            .setId(MESSAGE_ID)
            .setRegistrarId(getRegistrarIdForFlow())
            .setEventTime(clock.nowUtc().minusDays(1))
            .setMsg("Some poll message.")
            .setParent(createHistoryEntryForEppResource(contact))
            .build());
    assertTransactionalFlow(true);
    assertThrows(InvalidMessageIdException.class, this::runFlow);
  }

  @TestOfyAndSql
  void testFailure_invalidId_stringInsteadOfNumeric() throws Exception {
    setEppInput("poll_ack.xml", ImmutableMap.of("MSGID", "ABC-12345"));
    assertTransactionalFlow(true);
    assertThrows(InvalidMessageIdException.class, this::runFlow);
  }

  @TestOfyAndSql
  void testFailure_invalidEppResourceClassId() throws Exception {
    setEppInput("poll_ack.xml", ImmutableMap.of("MSGID", "999-1-1-1"));
    assertTransactionalFlow(true);
    assertThrows(InvalidMessageIdException.class, this::runFlow);
  }

  @TestOfyAndSql
  void testFailure_missingId() throws Exception {
    setEppInput("poll_ack_missing_id.xml");
    assertTransactionalFlow(true);
    assertThrows(MissingMessageIdException.class, this::runFlow);
  }

  @TestOfyAndSql
  void testFailure_differentRegistrar() throws Exception {
    persistResource(
        new PollMessage.OneTime.Builder()
            .setId(MESSAGE_ID)
            .setRegistrarId("TheRegistrar")
            .setEventTime(clock.nowUtc().minusDays(1))
            .setMsg("Some poll message.")
            .setParent(createHistoryEntryForEppResource(domain))
            .build());
    assertTransactionalFlow(true);
    assertThrows(NotAuthorizedToAckMessageException.class, this::runFlow);
  }

  @TestOfyAndSql
  void testFailure_messageInFuture() throws Exception {
    persistResource(
        new PollMessage.OneTime.Builder()
            .setId(MESSAGE_ID)
            .setRegistrarId(getRegistrarIdForFlow())
            .setEventTime(clock.nowUtc().plusDays(1))
            .setMsg("Some poll message.")
            .setParent(createHistoryEntryForEppResource(domain))
            .build());
    assertTransactionalFlow(true);
    Exception e = assertThrows(MessageDoesNotExistException.class, this::runFlow);
    assertThat(e).hasMessageThat().contains(String.format("(1-3-EXAMPLE-4-%d-2011)", MESSAGE_ID));
  }
}
