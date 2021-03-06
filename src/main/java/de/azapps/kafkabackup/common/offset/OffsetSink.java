package de.azapps.kafkabackup.common.offset;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.errors.RetriableException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

public class OffsetSink {
    private final Path targetDir;
    private Map<TopicPartition, OffsetStoreFile> topicOffsets = new HashMap<>();
    private List<String> consumerGroups = new ArrayList<>();
    private AdminClient adminClient;

    public OffsetSink(AdminClient adminClient, Path targetDir) {
        this.adminClient = adminClient;
        this.targetDir = targetDir;
    }

    public void syncConsumerGroups() {
        try {
            consumerGroups = adminClient.listConsumerGroups().all().get().stream().map(ConsumerGroupListing::groupId).collect(Collectors.toList());
        } catch (InterruptedException | ExecutionException e) {
            throw new RetriableException(e);
        }
    }

    public void syncOffsets() throws IOException {
        boolean error = false;
        for (String consumerGroup : consumerGroups) {
            try {
                syncOffsetsForGroup(consumerGroup);
            } catch (IOException e) {
                e.printStackTrace();
                error = true;
            }
        }
        if (error) {
            throw new IOException("syncOffsets() threw an IOException");
        }
    }

    private void syncOffsetsForGroup(String consumerGroup) throws IOException {
        Map<TopicPartition, OffsetAndMetadata> topicOffsetsAndMetadata;
        try {
            topicOffsetsAndMetadata = adminClient.listConsumerGroupOffsets(consumerGroup).partitionsToOffsetAndMetadata().get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RetriableException(e);
        }
        for (TopicPartition tp : topicOffsetsAndMetadata.keySet()) {
            if (validTopic(tp.topic())) {
                if (!this.topicOffsets.containsKey(tp)) {
                    this.topicOffsets.put(tp, new OffsetStoreFile(targetDir, tp));
                }
                OffsetStoreFile offsets = this.topicOffsets.get(tp);
                offsets.put(consumerGroup, topicOffsetsAndMetadata.get(tp).offset());
            }
        }
    }

    private boolean validTopic(String topic) {
        return Files.isDirectory(Paths.get(targetDir.toString(), topic));
    }

    public void flush() throws IOException {
        boolean error = false;
        for (OffsetStoreFile offsetStoreFile : topicOffsets.values()) {
            try {
                offsetStoreFile.flush();
            } catch (IOException e) {
                e.printStackTrace();
                error = true;
            }
        }
        if (error) {
            throw new IOException("syncOffsets() threw an IOException");
        }
    }

    public void close() throws IOException {
        flush();
    }

    private static class OffsetStoreFile {
        private Map<String, Long> groupOffsets = new HashMap<>();

        private ObjectMapper mapper = new ObjectMapper();
        private Path storeFile;

        OffsetStoreFile(Path targetDir, TopicPartition topicPartition) throws IOException {
            storeFile = OffsetUtils.offsetStoreFile(targetDir, topicPartition);
            if (!Files.isRegularFile(storeFile)) {
                Files.createFile(storeFile);
            }
            if (Files.size(storeFile) > 0) {
                groupOffsets = mapper.readValue(storeFile.toFile(), Map.class);
            }
        }

        void put(String consumerGroup, long offset) {
            groupOffsets.put(consumerGroup, offset);
        }

        void flush() throws IOException {
            mapper.writeValue(storeFile.toFile(), groupOffsets);
        }
    }
}
