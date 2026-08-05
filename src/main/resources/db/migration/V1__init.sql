-- AI News Digest 초기 스키마
-- 엔티티 4종: 구독자 / 다이제스트 / 다이제스트 항목 / 발송 이력

create table subscriber (
    id                   bigserial primary key,
    chat_id              bigint      not null unique,
    status               varchar(20) not null,
    source               varchar(50),
    subscribed_at        timestamptz not null,
    unsubscribed_at      timestamptz,
    consecutive_failures int         not null default 0
);

comment on column subscriber.status is 'ACTIVE | UNSUBSCRIBED';
comment on column subscriber.source is '텔레그램 deep link start payload (유입 경로)';

-- digest_date UNIQUE가 하루 1회 발송의 멱등성을 보장한다. 이 제약을 제거하지 말 것.
create table digest (
    id           bigserial primary key,
    digest_date  date        not null unique,
    status       varchar(20) not null,
    message_text text,
    generated_at timestamptz,
    sent_at      timestamptz
);

comment on column digest.status is 'PENDING | SENT | EMPTY | FAILED';

create table digest_item (
    id             bigserial primary key,
    digest_id      bigint       not null references digest (id) on delete cascade,
    position       int          not null,
    title_ko       varchar(200) not null,
    summary_ko     text         not null,
    source_url     text         not null,
    normalized_url text         not null,
    source_domain  varchar(100) not null,
    score          int          not null
);

comment on column digest_item.normalized_url is '중복 발송 방지용 정규화 URL (최근 7일 대조)';
comment on column digest_item.score is 'LLM 선별 점수 1~5. 3점 이상만 채택된다.';

create index idx_digest_item_normalized_url on digest_item (normalized_url);
create index idx_digest_item_digest_id on digest_item (digest_id);

create table delivery_log (
    id            bigserial primary key,
    digest_id     bigint      not null references digest (id),
    subscriber_id bigint      not null references subscriber (id),
    status        varchar(20) not null,
    error_code    varchar(50),
    attempted_at  timestamptz not null
);

comment on column delivery_log.status is 'SUCCESS | FAILED';
comment on column delivery_log.error_code is '텔레그램 오류 코드 (403, 429 등)';

create index idx_delivery_log_digest_id on delivery_log (digest_id);
