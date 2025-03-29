package app.soundcheck.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import app.soundcheck.identity.AccountPurgeStep;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The purge's work list: accounts that fail stay at the head of the list, oldest first.
 * A full batch of them must not keep the run from reaching the accounts behind.
 */
class AccountPurgeServiceTest {

    @Test
    void accountsThatCannotBePurgedDoNotHoldUpTheOnesBehindThem() {
        List<UUID> stuck = Stream.generate(UUID::randomUUID)
                .limit(AccountPurgeService.BATCH)
                .toList();
        List<UUID> fine = Stream.generate(UUID::randomUUID).limit(3).toList();
        List<UUID> due = new ArrayList<>(stuck);
        due.addAll(fine);

        // A fake work list: purged accounts leave it, the others keep their place.
        UserRepository users = mock(UserRepository.class);
        when(users.deletedBefore(any(), anyInt(), anyInt())).thenAnswer(call -> {
            int skip = Math.min(call.getArgument(1, Integer.class), due.size());
            int end = Math.min(skip + call.getArgument(2, Integer.class), due.size());
            return List.copyOf(due.subList(skip, end));
        });
        doAnswer(call -> due.remove(call.getArgument(0, UUID.class)))
                .when(users)
                .delete(any());
        AccountPurgeStep failingForSome = new AccountPurgeStep() {
            @Override
            public int order() {
                return 1;
            }

            @Override
            public void purge(UUID userId) {
                if (stuck.contains(userId)) {
                    throw new IllegalStateException("storage is down");
                }
            }
        };
        AccountPurgeService purge = new AccountPurgeService(
                users,
                List.of(failingForSome),
                new TransactionTemplate(mock(PlatformTransactionManager.class)),
                Clock.systemUTC());

        assertThat(purge.purgeDue()).isEqualTo(3);
        assertThat(due).containsExactlyElementsOf(stuck);
    }
}
