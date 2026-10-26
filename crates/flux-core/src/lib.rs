//! FLUX core: identity, pairing, pinned-mTLS transport and the streaming, resumable,
//! integrity-verified file-transfer engine. UI-agnostic; used by the CLI and (next) the Tauri app.

pub mod discovery;
pub mod error;
pub mod events;
pub mod identity;
pub mod node;
pub mod pairing;
pub mod outbox;
pub mod paths;
pub mod protocol;
pub mod receive;
pub mod send;
pub mod share;
pub mod store;
pub mod tls;

pub use error::{FluxError, Result};
pub use identity::Identity;
pub use node::{Approver, Decision, IncomingRequest, Limits, Node, RejectAll};
pub use send::{Control, RetryPolicy, Target};
pub use store::Store;
