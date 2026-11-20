package com.ledger.Wallet;

import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;

 // In-process "gate" in front of the database. Threads queue here (holding NO database connection)
 // instead of queueing on row locks inside the database. The atomic conditional UPDATEs remain the
 // actual correctness guarantee (and protect across several app instances); this just removes
 // in-JVM lock contention, which keeps connections free and avoids long row-lock waits.
 // Striped: a fixed number of locks, chosen by account-number hash. Locks are always taken in
 // ascending stripe order, so two threads can never wait on each other in a cycle.
@Component
public class AccountLockManager {

    private static final int STRIPES = 64;
    private final ReentrantLock[] locks = new ReentrantLock[STRIPES];

    public AccountLockManager() {
        for (int i = 0; i < STRIPES; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    // Locks both accounts (in a deadlock-free order). Use in try-with-resources
    public Held acquire(String accountA, String accountB) {
        int a = stripe(accountA);
        int b = stripe(accountB);
        int first = Math.min(a, b);
        int second = Math.max(a, b);

        locks[first].lock();
        if (second != first) {
            locks[second].lock();
        }
        return new Held(first, second);
    }

    private static int stripe(String account) {
        return Math.floorMod(account.hashCode(), STRIPES);
    }

    public final class Held implements AutoCloseable {
        private final int first;
        private final int second;

        private Held(int first, int second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public void close() {
            if (second != first) {
                locks[second].unlock();
            }
            locks[first].unlock();
        }
    }
}
