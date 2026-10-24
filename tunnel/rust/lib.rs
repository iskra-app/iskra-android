//! Клиентская половина слепого моста, для Android (docs/PROMPT.md, 9.3).
//!
//! На iOS протокол потока зеркалирован на Swift и держится тестами-вопросами. Здесь зеркала нет:
//! кадр, окно и сборка — это `iskra-tunnel-proto`, тот же код, что у моста, и формат проводов
//! совпадает с мостом по построению. Этот крейт добавляет к нему только то, чего нет в proto и что
//! нужно клиенту: ключ устройства и адрес моста, событие nostr вокруг кадра (подпись BIP-340 и
//! доказательство работы NIP-13 для `open`) и проверку чужих событий — и отдаёт всё это Kotlin
//! через uniffi.
//!
//! **Ввода-вывода нет.** Ни сокета, ни relay'я, ни таймера, ни tokio: «вот кадр → вот событие»,
//! «вот событие → вот кадр», «вот сегмент → вот что теперь можно писать в сокет». Сокеты, relay'и и
//! время живут в Kotlin (`:core/transport`), и правила 9.5 — не ждать `OK`, публиковать на все
//! relay'и, поднимать листенер на том же порту — проверяются там. Даже часы сюда не ходят: окно
//! приёма спрашивает «который час» у вызывающего, в миллисекундах от своего рождения.

use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use iskra_tunnel_proto::stream::{self as proto_stream, Inbound, Outbound};
use iskra_tunnel_proto::{
    ProtoError, StreamFrame, StreamOp, DEFAULT_POW_BITS, STREAM_PROTOCOL_VERSION, DEFAULT_SEGMENT, KIND_STREAM_BRIDGE,
    KIND_STREAM_CLIENT, MAX_POW_BITS,
};
use nostr_sdk::hashes::{sha256, Hash, HashEngine};
use nostr_sdk::nips::nip13::get_leading_zero_bits;
use nostr_sdk::nips::nip44::v2::{self as nip44v2, ConversationKey};
use nostr_sdk::prelude::*;

uniffi::setup_scaffolding!();

// ---------------------------------------------------------------------------- числа протокола

/// Числа протокола, которые нужны вводу-выводу на стороне Kotlin. Отдаются отсюда, а не
/// переписываются константами: число, переписанное руками, — второе место, где оно может
/// разойтись с мостом.
#[derive(uniffi::Record, Debug, Clone)]
pub struct TunnelLimits {
    /// Байт потока в сегменте, как их режет отправитель.
    pub default_segment: u32,
    /// Сколько сокет может молчать, прежде чем сказанное уйдёт сегментом.
    pub coalesce_millis: u64,
    /// Сколько ждать дыру, прежде чем сбросить поток.
    pub gap_timeout_millis: u64,
    /// Сложность `open`, пока мост не попросил другую.
    pub default_pow_bits: u8,
    /// Больше этого клиент не майнит, что бы ни просили.
    pub max_pow_bits: u8,
    pub kind_stream_client: u16,
    pub kind_stream_bridge: u16,
}

#[uniffi::export]
pub fn tunnel_limits() -> TunnelLimits {
    TunnelLimits {
        default_segment: DEFAULT_SEGMENT as u32,
        coalesce_millis: proto_stream::COALESCE.as_millis() as u64,
        gap_timeout_millis: proto_stream::GAP_TIMEOUT.as_millis() as u64,
        default_pow_bits: DEFAULT_POW_BITS,
        max_pow_bits: MAX_POW_BITS,
        kind_stream_client: KIND_STREAM_CLIENT,
        kind_stream_bridge: KIND_STREAM_BRIDGE,
    }
}

/// Свежий id потока — UUID: это ключ, который держит мост, и выбирать его клиенту.
#[uniffi::export]
pub fn new_stream_id() -> String {
    iskra_tunnel_proto::new_stream_id()
}

/// Сложность, которую просит отказ `pow <bits>`, или `None` для любой другой причины — и для
/// сложности выше потолка.
#[uniffi::export]
pub fn pow_required(reason: String) -> Option<u8> {
    iskra_tunnel_proto::pow_required(&reason)
}

/// Слова, которыми кончается поток (`reason` в proto): одно на каждый способ, одинаковое с обеих
/// сторон. Отдаются отсюда, чтобы Kotlin превращал в предложения ровно те слова, что шлёт мост, а не
/// их переписанную копию (`BridgeRefusal`).
#[derive(uniffi::Record, Debug, Clone)]
pub struct StreamReasons {
    pub bad_port: String,
    pub not_public: String,
    pub not_allowed: String,
    pub not_matrix: String,
    pub no_such_host: String,
    pub not_tls: String,
    pub no_sni: String,
    pub sni_mismatch: String,
    pub busy: String,
    pub gap: String,
    pub window: String,
    pub protocol: String,
    pub unreachable: String,
    pub upstream: String,
    pub idle: String,
    pub expired: String,
    pub closed: String,
    pub pow: String,
}

#[uniffi::export]
pub fn stream_reasons() -> StreamReasons {
    use iskra_tunnel_proto::reason;
    StreamReasons {
        bad_port: reason::BAD_PORT.into(),
        not_public: reason::NOT_PUBLIC.into(),
        not_allowed: reason::NOT_ALLOWED.into(),
        not_matrix: reason::NOT_MATRIX.into(),
        no_such_host: reason::NO_SUCH_HOST.into(),
        not_tls: reason::NOT_TLS.into(),
        no_sni: reason::NO_SNI.into(),
        sni_mismatch: reason::SNI_MISMATCH.into(),
        busy: reason::BUSY.into(),
        gap: reason::GAP.into(),
        window: reason::WINDOW.into(),
        protocol: reason::PROTOCOL.into(),
        unreachable: reason::UNREACHABLE.into(),
        upstream: reason::UPSTREAM.into(),
        idle: reason::IDLE.into(),
        expired: reason::EXPIRED.into(),
        closed: reason::CLOSED.into(),
        pow: reason::POW.into(),
    }
}

// ---------------------------------------------------------------------------- ошибки

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum TunnelError {
    /// Байты, которые не ключ nostr.
    #[error("not a key")]
    NotAKey,
    /// Кадр, который proto не принимает: не те поля для своего `op`, слишком длинный, не та версия.
    #[error("malformed frame: {reason}")]
    Malformed { reason: String },
    /// Другая сторона сделала то, чего правильная реализация не делает. `reason` — слово для `rst`
    /// (`window` или `protocol`), то же, что сказал бы мост.
    #[error("stream violation: {reason}")]
    Violation { reason: String },
    /// Событие не собралось или не подписалось.
    #[error("cannot sign")]
    Signing,
}

impl From<ProtoError> for TunnelError {
    fn from(error: ProtoError) -> Self {
        TunnelError::Malformed {
            reason: error.to_string(),
        }
    }
}

impl From<proto_stream::Violation> for TunnelError {
    fn from(violation: proto_stream::Violation) -> Self {
        TunnelError::Violation {
            reason: violation.reason().to_string(),
        }
    }
}

// ---------------------------------------------------------------------------- кадр

#[derive(uniffi::Enum, Debug, Clone, Copy, PartialEq, Eq)]
pub enum FrameOp {
    Open,
    Opened,
    Data,
    Ack,
    Fin,
    Rst,
}

impl From<FrameOp> for StreamOp {
    fn from(op: FrameOp) -> Self {
        match op {
            FrameOp::Open => StreamOp::Open,
            FrameOp::Opened => StreamOp::Opened,
            FrameOp::Data => StreamOp::Data,
            FrameOp::Ack => StreamOp::Ack,
            FrameOp::Fin => StreamOp::Fin,
            FrameOp::Rst => StreamOp::Rst,
        }
    }
}

impl From<StreamOp> for FrameOp {
    fn from(op: StreamOp) -> Self {
        match op {
            StreamOp::Open => FrameOp::Open,
            StreamOp::Opened => FrameOp::Opened,
            StreamOp::Data => FrameOp::Data,
            StreamOp::Ack => FrameOp::Ack,
            StreamOp::Fin => FrameOp::Fin,
            StreamOp::Rst => FrameOp::Rst,
        }
    }
}

/// Один кадр одного потока — `StreamFrame` proto, с сегментом уже в байтах, а не в base64.
///
/// Собирается функциями `frame_*` ниже — теми же конструкторами proto, что у моста, — а не руками:
/// у каждого `op` свой набор полей, и `validate` в proto откажет любому другому.
#[derive(uniffi::Record, Debug, Clone, PartialEq, Eq)]
pub struct Frame {
    pub sid: String,
    pub op: FrameOp,
    #[uniffi(default = None)]
    pub host: Option<String>,
    #[uniffi(default = None)]
    pub port: Option<u16>,
    #[uniffi(default = None)]
    pub seq: Option<u32>,
    #[uniffi(default = None)]
    pub ack: Option<u32>,
    #[uniffi(default = None)]
    pub data: Option<Vec<u8>>,
    #[uniffi(default = None)]
    pub reason: Option<String>,
}

impl Frame {
    fn from_wire(frame: StreamFrame) -> Result<Self, ProtoError> {
        let data = match frame.data {
            Some(_) => Some(frame.payload()?),
            None => None,
        };
        Ok(Frame {
            sid: frame.sid,
            op: frame.op.into(),
            host: frame.host,
            port: frame.port,
            seq: frame.seq,
            ack: frame.ack,
            data,
            reason: frame.reason,
        })
    }

    /// Обратно в провод — все поля как есть, и с проверкой proto: кадр, который мост не примет
    /// (поле, которого у этого `op` нет, или нет нужного), не уходит.
    fn to_wire(&self) -> Result<StreamFrame, TunnelError> {
        use nostr_sdk::base64::prelude::*;
        let frame = StreamFrame {
            v: STREAM_PROTOCOL_VERSION,
            sid: self.sid.clone(),
            op: self.op.into(),
            host: self.host.clone(),
            port: self.port,
            seq: self.seq,
            ack: self.ack,
            data: self.data.as_ref().map(|bytes| BASE64_STANDARD.encode(bytes)),
            reason: self.reason.clone(),
        };
        frame.validate()?;
        Ok(frame)
    }
}

fn wired(frame: StreamFrame) -> Frame {
    Frame::from_wire(frame).expect("a frame made here decodes")
}

/// Клиент → мост: соединись с `host:port`, и вот первые байты — сегмент 0.
#[uniffi::export]
pub fn frame_open(sid: String, host: String, port: u16, first: Vec<u8>) -> Frame {
    wired(StreamFrame::open(&sid, &host, port, &first))
}

/// Мост → клиент: TCP-соединение поднято. Клиенту — только для стоящего в тестах моста.
#[uniffi::export]
pub fn frame_opened(sid: String) -> Frame {
    wired(StreamFrame::opened(&sid))
}

#[uniffi::export]
pub fn frame_data(sid: String, seq: u32, bytes: Vec<u8>) -> Frame {
    wired(StreamFrame::data(&sid, seq, &bytes))
}

#[uniffi::export]
pub fn frame_ack(sid: String, ack: u32) -> Frame {
    wired(StreamFrame::ack(&sid, ack))
}

#[uniffi::export]
pub fn frame_fin(sid: String, seq: u32) -> Frame {
    wired(StreamFrame::fin(&sid, seq))
}

#[uniffi::export]
pub fn frame_rst(sid: String, reason: String) -> Frame {
    wired(StreamFrame::rst(&sid, &reason))
}

// ---------------------------------------------------------------------------- ключи и адрес

/// Новый секретный ключ устройства, 32 байта. Хранит его Kotlin — в Keystore, отдельно от сессий.
#[uniffi::export]
pub fn generate_secret_key() -> Vec<u8> {
    SecretKey::generate().to_secret_bytes().to_vec()
}

/// Открытый ключ к секретному, hex. Это обратный адрес туннеля: ответ моста адресован ему.
#[uniffi::export]
pub fn public_key_hex(secret_key: Vec<u8>) -> Result<String, TunnelError> {
    Ok(keys(&secret_key)?.public_key().to_hex())
}

/// Ключ так, как его пишут люди, — `npub1…`. Для экрана «Подключение»: его добавляют в
/// `ALLOWED_PUBKEYS` закрытого моста.
#[uniffi::export]
pub fn npub(public_key_hex: String) -> Result<String, TunnelError> {
    let key = PublicKey::from_hex(&public_key_hex).map_err(|_| TunnelError::NotAKey)?;
    key.to_bech32().map_err(|_| TunnelError::NotAKey)
}

/// Что напечатано в поле адреса моста (`BridgeAddress` на iOS).
#[derive(uniffi::Enum, Debug, Clone, PartialEq, Eq)]
pub enum BridgeAddress {
    /// Ключ моста: 32 байта x-only, hex.
    Key { public_key_hex: String },
    /// Поле пустое — моста нет. Не ошибка: с пустого поля начинают все.
    Empty,
    /// `nsec1…` — чей-то *секретный* ключ. Называется по имени, а не превращается молча в открытый.
    LooksLikeASecretKey,
    NotAKey,
}

/// Адрес моста из того, что вставил человек: `npub1…` или 64 знака hex.
///
/// Ключ должен быть точкой на кривой: hex, который разобрался, ещё не ключ, и ключ-не-точка
/// сломался бы на первом ECDH — далеко от поля, в которое его напечатали.
#[uniffi::export]
pub fn parse_bridge_address(text: String) -> BridgeAddress {
    let trimmed = text.trim();
    if trimmed.is_empty() {
        return BridgeAddress::Empty;
    }
    if trimmed.to_ascii_lowercase().starts_with("nsec1") {
        return BridgeAddress::LooksLikeASecretKey;
    }
    let key = if trimmed.to_ascii_lowercase().starts_with("npub1") {
        PublicKey::from_bech32(trimmed).ok()
    } else if trimmed.len() == 64 {
        PublicKey::from_hex(trimmed).ok()
    } else {
        None
    };
    match key {
        Some(key) if key.xonly().is_ok() => BridgeAddress::Key {
            public_key_hex: key.to_hex(),
        },
        _ => BridgeAddress::NotAKey,
    }
}

fn keys(secret: &[u8]) -> Result<Keys, TunnelError> {
    let secret = SecretKey::from_slice(secret).map_err(|_| TunnelError::NotAKey)?;
    Ok(Keys::new(secret))
}

fn public_key(hex: &str) -> Result<PublicKey, TunnelError> {
    let key = PublicKey::from_hex(hex).map_err(|_| TunnelError::NotAKey)?;
    key.xonly().map_err(|_| TunnelError::NotAKey)?;
    Ok(key)
}

// ---------------------------------------------------------------------------- события

/// Id события, если оно подлинное: id сходится с содержимым и подпись — с ключом автора.
///
/// Для пула relay'ев, который отбрасывает дубликаты по id: проверяется *до* того, как id
/// запомнен, иначе один враждебный relay, приславший подделку с id настоящего ответа моста первым,
/// сделал бы настоящие копии со всех честных relay'ев «дубликатами» (`NostrRelayPool` на iOS).
#[uniffi::export]
pub fn verify_event(json: String) -> Option<String> {
    let event = Event::from_json(&json).ok()?;
    event.verify().ok()?;
    Some(event.id.to_hex())
}

/// Кадры в события nostr и обратно, для одной пары ключей: этого устройства и моста.
///
/// Конверсейшн-ключ NIP-44 — ECDH своего секрета и чужого открытого ключа, поэтому обе стороны
/// получают один и тот же из противоположных половин, и ключа в событии нет.
#[derive(uniffi::Object)]
pub struct FrameSealer {
    keys: Keys,
    peer: PublicKey,
    outgoing: Kind,
    incoming: Kind,
}

#[uniffi::export]
impl FrameSealer {
    /// Клиент: свои события — kind 20010, ответы моста — 20011 от `bridge`.
    #[uniffi::constructor]
    pub fn for_client(secret_key: Vec<u8>, bridge_public_key_hex: String) -> Result<Arc<Self>, TunnelError> {
        Ok(Arc::new(Self {
            keys: keys(&secret_key)?,
            peer: public_key(&bridge_public_key_hex)?,
            outgoing: Kind::Custom(KIND_STREAM_CLIENT),
            incoming: Kind::Custom(KIND_STREAM_BRIDGE),
        }))
    }

    /// Мост — наоборот. Только для моста-заглушки в тестах: он гоняет тот же код, что клиент,
    /// и вопросы к протоколу задаются одной программе.
    #[uniffi::constructor]
    pub fn for_bridge(secret_key: Vec<u8>, client_public_key_hex: String) -> Result<Arc<Self>, TunnelError> {
        Ok(Arc::new(Self {
            keys: keys(&secret_key)?,
            peer: public_key(&client_public_key_hex)?,
            outgoing: Kind::Custom(KIND_STREAM_BRIDGE),
            incoming: Kind::Custom(KIND_STREAM_CLIENT),
        }))
    }

    pub fn our_public_key_hex(&self) -> String {
        self.keys.public_key().to_hex()
    }

    pub fn peer_public_key_hex(&self) -> String {
        self.peer.to_hex()
    }

    /// Кадр — в подписанное событие, JSON, готовый к `["EVENT", …]`.
    ///
    /// `pow_bits` — сложность NIP-13: у `open` та, что просит мост, у остальных ноль — сегменты
    /// внутри потока доказательств не несут, иначе фотография стоила бы сотни доказательств.
    pub fn seal(&self, frame: Frame, pow_bits: u8) -> Result<String, TunnelError> {
        let wire = frame.to_wire()?;
        let content = iskra_tunnel_proto::seal_frame(self.keys.secret_key(), &self.peer, &wire)?;
        let tags = vec![Tag::public_key(self.peer)];
        let event = mined(&self.keys, self.outgoing, tags, content, pow_bits.min(MAX_POW_BITS))?;
        Ok(event.as_json())
    }

    /// Событие с relay'я — в кадр, или `None`: не наше, не от того, не расшифровалось, не прошло
    /// проверку proto. На открытом relay'е это обычное дело, а не ошибка, и о содержимом такого
    /// события не стоит ни слова.
    pub fn open(&self, event_json: String) -> Option<Frame> {
        let event = Event::from_json(&event_json).ok()?;
        if event.kind != self.incoming || event.pubkey != self.peer {
            return None;
        }
        event.verify().ok()?;
        let frame = iskra_tunnel_proto::open_frame(self.keys.secret_key(), &self.peer, &event.content).ok()?;
        Frame::from_wire(frame).ok()
    }

    /// Фильтр подписки: кадры от собеседника, адресованные нам, начиная с `since` (секунды Unix).
    pub fn subscription_filter(&self, since: u64) -> String {
        serde_json::json!({
            "kinds": [self.incoming.as_u16()],
            "authors": [self.peer.to_hex()],
            "#p": [self.keys.public_key().to_hex()],
            "since": since,
        })
        .to_string()
    }
}

/// Подписанное событие с доказательством работы `bits` (NIP-13): тег `["nonce", n, bits]`, и `n`
/// перебирается, пока id не начнётся с `bits` нулевых бит.
///
/// Сериализация события для id собирается *один раз* — с меткой на месте `n`, — и на каждой
/// попытке хешируется только `n` и хвост: при шестнадцати битах это шестьдесят пять тысяч попыток,
/// и сборка JSON на каждой из них стоила на iOS секунду (`NostrEvent.mined`). Сериализация та же,
/// что у `EventId::new` в nostr, — `[0, pubkey, created_at, kind, tags, content]` через serde_json,
/// — и подписанное событие проверяется ещё раз: id, намайненный не так, не уходит.
fn mined(keys: &Keys, kind: Kind, tags: Vec<Tag>, content: String, bits: u8) -> Result<Event, TunnelError> {
    let public_key = keys.public_key();
    if bits == 0 {
        return UnsignedEvent::new(public_key, Timestamp::now(), kind, tags, content)
            .sign_with_keys(keys)
            .map_err(|_| TunnelError::Signing);
    }

    // Заглавные латинские буквы: hex ключа и id их не содержит, а теги идут в сериализации раньше
    // содержимого, так что первое вхождение — наше.
    const MARK: &str = "ISKRANONCEMARK";
    loop {
        let created_at = Timestamp::now();
        let mut serialized_tags: Vec<Vec<String>> = tags.iter().map(|tag| tag.as_slice().to_vec()).collect();
        serialized_tags.push(vec!["nonce".into(), MARK.into(), bits.to_string()]);
        let serialized = serde_json::json!([
            0,
            public_key.to_hex(),
            created_at.as_secs(),
            kind.as_u16(),
            serialized_tags,
            content,
        ])
        .to_string();
        let at = serialized.find(MARK).ok_or(TunnelError::Signing)?;
        let (prefix, suffix) = (&serialized[..at], &serialized[at + MARK.len()..]);

        let mut engine = sha256::Hash::engine();
        engine.input(prefix.as_bytes());
        // Ожидаемо 2^bits попыток; 2^(bits+6) без попадания — вероятность ниже 10^-27. Тогда — новая
        // секунда и заново, а не вечный цикл.
        let budget: u64 = 1 << (u64::from(bits) + 6);
        for nonce in 0..budget {
            let mut attempt = engine.clone();
            attempt.input(nonce.to_string().as_bytes());
            attempt.input(suffix.as_bytes());
            let id = sha256::Hash::from_engine(attempt).to_byte_array();
            if get_leading_zero_bits(id) < bits {
                continue;
            }
            let mut tags = tags.clone();
            tags.push(Tag::parse(["nonce", &nonce.to_string(), &bits.to_string()]).map_err(|_| TunnelError::Signing)?);
            let event = UnsignedEvent::new(public_key, created_at, kind, tags, content.clone())
                .sign_with_keys(keys)
                .map_err(|_| TunnelError::Signing)?;
            if !event.check_pow(bits) {
                return Err(TunnelError::Signing);
            }
            return Ok(event);
        }
    }
}

// ---------------------------------------------------------------------------- окна потока

/// Окно отправителя — `Outbound` из proto. Двигается только подтверждениями: то, что одна сторона
/// прочла из сокета, никогда не больше того, что другая записала в свой. Полное окно перестаёт
/// читать сокет, и TCP просит сервер подождать — это и есть вся обратная связь.
#[derive(uniffi::Object)]
pub struct OutboundWindow(Mutex<Outbound>);

#[uniffi::export]
impl OutboundWindow {
    /// Окно стороны, которая не открывала поток (мост-заглушка).
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(Self(Mutex::new(Outbound::default())))
    }

    /// Окно клиента, чей сегмент 0 уже ушёл в `open`, `first_len` байт.
    #[uniffi::constructor]
    pub fn after_open(first_len: u32) -> Arc<Self> {
        Arc::new(Self(Mutex::new(Outbound::after_open(first_len as usize))))
    }

    /// Может ли сегмент в `len` байт уйти сейчас. Пустое окно пропускает один сегмент любого размера.
    pub fn has_room(&self, len: u32) -> bool {
        self.0.lock().unwrap().has_room(len as usize)
    }

    /// Учесть ушедший сегмент; его `seq`.
    pub fn push(&self, len: u32) -> u32 {
        self.0.lock().unwrap().push(len as usize)
    }

    /// `seq` следующего сегмента — и `seq` для `fin`.
    pub fn next_seq(&self) -> u32 {
        self.0.lock().unwrap().next_seq()
    }

    /// Подтверждение. Старое, пришедшее поздно с медленного relay'я, ничего не меняет.
    pub fn ack(&self, ack: u32) -> Result<(), TunnelError> {
        Ok(self.0.lock().unwrap().ack(ack)?)
    }

    pub fn unacked_bytes(&self) -> u64 {
        self.0.lock().unwrap().unacked_bytes() as u64
    }
}

/// Сборка получателя — `Inbound` из proto: сегменты приходят не по порядку и с нескольких relay'ев,
/// в сокет идёт только непрерывное начало. Дыру долго не ждут: relay'и не хранят эфемерные события,
/// и сегмент, который не доставил ни один, не придёт.
///
/// Время — от вызывающего, в миллисекундах от любого начала, лишь бы одного на всю жизнь окна:
/// часов здесь нет, и тест может прокрутить таймаут дыры, не ожидая его.
#[derive(uniffi::Object)]
pub struct InboundReassembly {
    inbound: Mutex<Inbound>,
    origin: Instant,
}

impl InboundReassembly {
    fn at(&self, now_millis: u64) -> Instant {
        self.origin + Duration::from_millis(now_millis)
    }
}

#[uniffi::export]
impl InboundReassembly {
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            inbound: Mutex::new(Inbound::new()),
            origin: Instant::now(),
        })
    }

    /// Один сегмент. Возвращает то, что теперь непрерывно, по порядку, для сокета: возможно ничего
    /// (дубликат, сегмент за дырой), возможно несколько (тот, что закрыл дыру).
    pub fn accept(&self, seq: u32, bytes: Vec<u8>, now_millis: u64) -> Result<Vec<Vec<u8>>, TunnelError> {
        let at = self.at(now_millis);
        Ok(self.inbound.lock().unwrap().accept(seq, bytes, at)?)
    }

    /// `fin` другой стороны. True, когда поток в эту сторону теперь целиком.
    pub fn finish(&self, seq: u32, now_millis: u64) -> Result<bool, TunnelError> {
        let at = self.at(now_millis);
        Ok(self.inbound.lock().unwrap().finish(seq, at)?)
    }

    pub fn is_complete(&self) -> bool {
        self.inbound.lock().unwrap().is_complete()
    }

    /// Ждут ли дыру дольше таймаута.
    pub fn gap_expired(&self, now_millis: u64) -> bool {
        let at = self.at(now_millis);
        self.inbound.lock().unwrap().gap_expired(at)
    }

    /// Сколько сегментов и байт записано в сокет. Возвращает `ack`, когда пора его послать:
    /// подтверждается записанное, а не полученное.
    pub fn written(&self, segments: u32, bytes: u64) -> Option<u32> {
        self.inbound.lock().unwrap().written(segments, bytes as usize)
    }
}

// ---------------------------------------------------------------------------- NIP-44 для векторов

/// Конверсейшн-ключ NIP-44 v2. Только для векторов NIP-44 через биндинги: так проверяется сам мост
/// uniffi — что байты доходят до Rust и обратно такими же (docs/PROMPT.md, 9.3).
#[uniffi::export]
pub fn nip44_conversation_key(secret_key_hex: String, public_key_hex: String) -> Result<Vec<u8>, TunnelError> {
    let secret = SecretKey::from_hex(&secret_key_hex).map_err(|_| TunnelError::NotAKey)?;
    let public = PublicKey::from_hex(&public_key_hex).map_err(|_| TunnelError::NotAKey)?;
    let key = ConversationKey::derive(&secret, &public).map_err(|_| TunnelError::NotAKey)?;
    Ok(key.as_bytes().to_vec())
}

/// Шифрование с *заданным* nonce — только для векторов. Туннель так не шифрует: `seal` берёт nonce
/// из системного генератора, внутри proto.
#[uniffi::export]
pub fn nip44_encrypt_with_nonce(conversation_key: Vec<u8>, nonce: Vec<u8>, plaintext: String) -> Result<String, TunnelError> {
    use nostr_sdk::base64::prelude::*;
    let key = ConversationKey::from_slice(&conversation_key).map_err(|_| TunnelError::NotAKey)?;
    let nonce: [u8; 32] = nonce.try_into().map_err(|_| TunnelError::Signing)?;
    let payload = nip44v2::encrypt_to_bytes_with_rng(&mut FixedNonce(nonce), &key, plaintext.as_bytes())
        .map_err(|error| TunnelError::Malformed { reason: error.to_string() })?;
    Ok(BASE64_STANDARD.encode(payload))
}

#[uniffi::export]
pub fn nip44_decrypt(conversation_key: Vec<u8>, payload: String) -> Result<String, TunnelError> {
    use nostr_sdk::base64::prelude::*;
    let key = ConversationKey::from_slice(&conversation_key).map_err(|_| TunnelError::NotAKey)?;
    let bytes = BASE64_STANDARD
        .decode(payload)
        .map_err(|error| TunnelError::Malformed { reason: error.to_string() })?;
    // `decrypt_to_bytes` версии не смотрит — её смотрит обёртка nostr уровнем выше, которой нужны
    // ключи, а не конверсейшн-ключ.
    if bytes.first() != Some(&2) {
        return Err(TunnelError::Malformed { reason: "unknown encryption version".into() });
    }
    let plaintext = nip44v2::decrypt_to_bytes(&key, &bytes).map_err(|error| TunnelError::Malformed { reason: error.to_string() })?;
    String::from_utf8(plaintext).map_err(|error| TunnelError::Malformed { reason: error.to_string() })
}

/// «Случайность», которая отдаёт заданный nonce: так `encrypt_to_bytes_with_rng` шифрует вектор.
struct FixedNonce([u8; 32]);

impl nostr_sdk::secp256k1::rand::RngCore for FixedNonce {
    fn next_u32(&mut self) -> u32 {
        unreachable!("NIP-44 asks for the nonce as bytes")
    }
    fn next_u64(&mut self) -> u64 {
        unreachable!("NIP-44 asks for the nonce as bytes")
    }
    fn fill_bytes(&mut self, dest: &mut [u8]) {
        dest.copy_from_slice(&self.0[..dest.len()]);
    }
    fn try_fill_bytes(&mut self, dest: &mut [u8]) -> Result<(), nostr_sdk::secp256k1::rand::Error> {
        self.fill_bytes(dest);
        Ok(())
    }
}

#[cfg(test)]
mod tests;
