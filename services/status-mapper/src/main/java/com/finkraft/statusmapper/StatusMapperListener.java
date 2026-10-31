package com.finkraft.statusmapper;

import com.finkraft.events.EventCodec;
import com.finkraft.events.EventEnvelope;
import com.finkraft.events.Topics;
import com.finkraft.events.payload.AspStatus;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
public class StatusMapperListener {

    private final StatusMapper mapper;

    public StatusMapperListener(StatusMapper mapper) {
        this.mapper = mapper;
    }

    @KafkaListener(topics = Topics.ASP_STATUS, concurrency = "${finkraft.status-mapper.concurrency:3}")
    public void onAspStatus(ConsumerRecord<String, String> record) {
        EventEnvelope<AspStatus> env = EventCodec.decode(record.value(), AspStatus.class);
        mapper.handle(env.payload());
    }
}
