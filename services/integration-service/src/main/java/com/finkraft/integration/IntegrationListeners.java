package com.finkraft.integration;

import com.finkraft.events.EventCodec;
import com.finkraft.events.EventEnvelope;
import com.finkraft.events.Topics;
import com.finkraft.events.payload.PollDue;
import com.finkraft.events.payload.RetryDue;
import com.finkraft.events.payload.SubmissionRequested;
import com.finkraft.support.Mdc;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class IntegrationListeners {

    private final SubmitService submits;
    private final PollService polls;

    public IntegrationListeners(SubmitService submits, PollService polls) {
        this.submits = submits;
        this.polls = polls;
    }

    @KafkaListener(topics = Topics.SUBMISSION_REQUESTED, concurrency = "${finkraft.integration.submit-concurrency}")
    public void onSubmissionRequested(ConsumerRecord<String, String> record) {
        EventEnvelope<SubmissionRequested> env = EventCodec.decode(record.value(), SubmissionRequested.class);
        try (var ignored = Mdc.invoice(env.payload().invoiceId())) {
            submits.submit(env.payload().invoiceId());
        }
    }

    @KafkaListener(topics = Topics.RETRY_DUE, concurrency = "${finkraft.integration.retry-concurrency}")
    public void onRetryDue(ConsumerRecord<String, String> record) {
        EventEnvelope<RetryDue> env = EventCodec.decode(record.value(), RetryDue.class);
        try (var ignored = Mdc.invoice(env.payload().invoiceId())) {
            submits.submit(env.payload().invoiceId());
        }
    }

    @KafkaListener(topics = Topics.POLL_DUE, concurrency = "${finkraft.integration.poll-concurrency}")
    public void onPollDue(ConsumerRecord<String, String> record) {
        EventEnvelope<PollDue> env = EventCodec.decode(record.value(), PollDue.class);
        try (var ignored = Mdc.invoice(env.payload().invoiceId())) {
            polls.poll(env);
        }
    }
}
