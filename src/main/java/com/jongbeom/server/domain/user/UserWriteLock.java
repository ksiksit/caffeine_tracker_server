package com.jongbeom.server.domain.user;

import com.jongbeom.server.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 단위 쓰기 잠금 — 그 사용자의 {@code users} 행을 {@code SELECT ... FOR UPDATE}로 잠근다.
 * 같은 사용자의 "읽고 → 판단하고 → 쓰는" 트랜잭션끼리 차례로 실행되게 해서,
 * 확인 후 삽입 경합(UNIQUE 위반)과 같은 행을 각자 읽고 덮어쓰는 갱신 유실을 막는다. 다른 사용자끼리는 서로 막지 않는다.
 *
 * <p><b>트랜잭션의 첫 문장으로 호출한다.</b> MySQL(InnoDB) 기본 격리 수준 REPEATABLE READ 에서는 트랜잭션의 첫
 * 일반 SELECT 시점에 스냅숏이 고정된다. 잠금보다 먼저 읽으면, 잠금을 기다리는 동안 앞 트랜잭션이 커밋한 내용이
 * 이후 읽기에서 보이지 않는다. 잠금이 먼저면 스냅숏은 잠금을 얻은 뒤에 잡힌다.
 * (H2 통합 테스트는 READ COMMITTED 라 순서를 어겨도 통과한다. 순서 위반은 실제 MySQL 에 같은 사용자의 동시 요청을 보내는
 * E2E {@code ConcurrentWriteE2ETest}가 잡는다 — CI 밖이므로 이 순서를 건드리면 {@code ./gradlew e2eTest}를 돌린다.)
 *
 * <p>잠금은 트랜잭션이 끝날 때 풀리므로, 바깥에 트랜잭션이 없으면 의미가 없다 — {@code MANDATORY}로 강제한다.
 */
@Component
@RequiredArgsConstructor
public class UserWriteLock {

    private final UserRepository userRepository;

    /** 사용자가 없으면(탈퇴 직후의 유효 토큰 등) 잠글 행이 없으므로 그냥 돌아간다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(Long userId) {
        userRepository.lockById(userId);
    }
}
