package com.mzerp.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import com.mzerp.support.IntegrationTest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;

@DisplayName("전표번호: 여러 명이 동시에 입력해도 번호가 겹치거나 비지 않는다 (ADR-0011)")
class EntryNumberConcurrencyTest extends IntegrationTest {

    private static final int WRITERS = 20;

    @Autowired
    private JournalService journal;

    @Test
    void concurrentCreatesGetUniqueSequentialNumbers() throws Exception {
        MockHttpSession admin = loginAsAdmin();
        Long companyId = createCompany(admin, "동시성 테스트");
        createFiscalYear(admin, companyId, 2026, 1);
        Long userId = users.findByLoginId(ADMIN_LOGIN).orElseThrow().getId();
        LocalDate date = LocalDate.of(2026, 5, 6);

        JournalService.EntryInput input = new JournalService.EntryInput(date, "동시 입력", List.of(
                new JournalService.LineInput(Side.DEBIT, "103", null, 10_000L, null),
                new JournalService.LineInput(Side.CREDIT, "101", null, 10_000L, null)));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(WRITERS);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < WRITERS; i++) {
                Callable<Integer> task = () -> {
                    start.await(); // 20개 스레드가 한꺼번에 출발
                    return journal.create(companyId, userId, input).entryNo();
                };
                results.add(pool.submit(task));
            }
            start.countDown();

            List<Integer> numbers = new ArrayList<>();
            for (Future<Integer> f : results) {
                numbers.add(f.get());
            }
            assertThat(numbers).containsExactlyInAnyOrderElementsOf(IntStream.rangeClosed(1, WRITERS).boxed().toList());
        } finally {
            pool.shutdownNow();
        }
    }
}
