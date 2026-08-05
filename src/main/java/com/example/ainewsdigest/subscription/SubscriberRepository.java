package com.example.ainewsdigest.subscription;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SubscriberRepository extends JpaRepository<Subscriber, Long> {

	Optional<Subscriber> findByChatId(long chatId);

	List<Subscriber> findAllByStatus(SubscriberStatus status);
}
