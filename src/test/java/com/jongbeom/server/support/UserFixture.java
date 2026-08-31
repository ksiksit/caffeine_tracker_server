package com.jongbeom.server.support;

import com.jongbeom.server.domain.user.entity.User;
import java.lang.reflect.Field;

/** 단위 테스트용 User 픽스처. */
public final class UserFixture {

    private UserFixture() {
    }

    /** id가 채워진 기본 User(a@b.com). id는 JPA 할당 필드라 setter가 없어 리플렉션으로 주입한다. */
    public static User withId(Long id) {
        return withId(User.create("a@b.com", "hashed", "테스터"), id);
    }

    /** 기존 User 인스턴스에 id 주입 (repository.save 목킹 등에서 사용). */
    public static User withId(User user, Long id) {
        try {
            Field idField = User.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(user, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return user;
    }
}
