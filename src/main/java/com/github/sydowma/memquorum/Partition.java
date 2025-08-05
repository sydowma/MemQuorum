package com.github.sydowma.memquorum;

import com.github.sydowma.memquorum.grpc.Entry;
import com.github.sydowma.memquorum.grpc.PartitionServiceGrpc;
import com.github.sydowma.memquorum.grpc.SyncRequest;
import com.github.sydowma.memquorum.grpc.SyncResponse;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class Partition {
    private volatile LinkedList<Entry> data = new LinkedList<>(); // 内存日志
    private final Object lock = new Object(); // 锁保护
    private final List<String> isr; // ISR列表 (follower地址，如 "host:port")
    private final int quorum;
    private final ExecutorService executor = Executors.newCachedThreadPool(); // Thread pool for async operations

    public Partition(List<String> isr, int quorum) {
        this.isr = isr;
        this.quorum = quorum;
    }

    public long append(Entry entry) throws QuorumException {
        synchronized (lock) {
            if (data == null) {
                data = new LinkedList<>();
            }
            long offset = data.size();
            data.add(entry);

            // 广播到followers
            CountDownLatch latch = new CountDownLatch(1);
            AtomicInteger confirms = new AtomicInteger(0);

            for (String followerAddr : isr) {
                executor.submit(() -> {
                    try {
                        ManagedChannel channel = ManagedChannelBuilder.forTarget(followerAddr).usePlaintext().build();
                        PartitionServiceGrpc.PartitionServiceBlockingStub stub = PartitionServiceGrpc.newBlockingStub(channel);
                        SyncResponse response = stub.sync(SyncRequest.newBuilder().setEntry(entry).setOffset(offset).build());
                        if (response.getSuccess()) {
                            if (confirms.incrementAndGet() >= quorum) {
                                latch.countDown();
                            }
                        }
                    } catch (StatusRuntimeException e) {
                        // 日志错误，但不抛出
                        System.err.println("Sync failed to " + followerAddr + ": " + e.getMessage());
                    }
                });
            }

            try {
                if (!latch.await(5, TimeUnit.SECONDS)) {
                    throw new QuorumException("Quorum not reached");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new QuorumException("Interrupted during quorum wait");
            }

            return offset;
        }
    }

    public List<Entry> read(long offset, int count) {
        synchronized (lock) {
            if (data == null || offset >= data.size()) {
                return new ArrayList<>();
            }
            int end = (int) Math.min(offset + count, data.size());
            return new ArrayList<>(data.subList((int) offset, end));
        }
    }

    // Follower同步方法（在gRPC服务中调用）
    public boolean sync(Entry entry, long offset) {
        synchronized (lock) {
            if (data == null) {
                data = new LinkedList<>();
            }
            // 简单追加，实际可检查offset一致性
            data.add(entry);
            return true;
        }
    }
}
