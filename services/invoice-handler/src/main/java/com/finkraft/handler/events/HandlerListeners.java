package com.finkraft.handler.events;

import com.finkraft.events.EventCodec;
import com.finkraft.events.EventEnvelope;
import com.finkraft.events.Topics;
import com.finkraft.events.payload.FixSuggested;
import com.finkraft.events.payload.InvoiceEvent;
import com.finkraft.support.Mdc;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Offsets are committed by the container only after these methods return (ack-mode record). */
@Component
public class HandlerListeners {

    private final InvoiceEventService events;
    private final FixSuggestionService fixes;

    public HandlerListeners(InvoiceEventService events, FixSuggestionService fixes) {
        this.events = events;
        this.fixes = fixes;
    }

    @KafkaListener(topics = Topics.INVOICE_EVENTS, concurrency = "${finkraft.handler.concurrency:3}")
    public void onInvoiceEvent(ConsumerRecord<String, String> record) {
        EventEnvelope<InvoiceEvent> env = EventCodec.decode(record.value(), InvoiceEvent.class);
        try (var ignored = Mdc.invoice(env.invoiceId())) {
            events.apply(env);
        }
    }

    @KafkaListener(topics = Topics.FIX_SUGGESTED)
    public void onFixSuggested(ConsumerRecord<String, String> record) {
        EventEnvelope<FixSuggested> env = EventCodec.decode(record.value(), FixSuggested.class);
        try (var ignored = Mdc.invoice(env.invoiceId())) {
            fixes.record(env);
        }
    }
}
