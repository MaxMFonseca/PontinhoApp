"""Canonical labels shared by dataset adapters, model export, and Android."""

from __future__ import annotations

RANK_CODES: tuple[str, ...] = (
    "A",
    "2",
    "3",
    "4",
    "5",
    "6",
    "7",
    "8",
    "9",
    "10",
    "J",
    "Q",
    "K",
)
SUIT_CODES: tuple[str, ...] = ("C", "D", "H", "S")

# This order mirrors Android's CLUBS, DIAMONDS, HEARTS, SPADES suit order and
# ACE..KING rank order. Model output indices must never be inferred from a source.
CARD_LABELS: tuple[str, ...] = tuple(
    f"{rank}{suit}" for suit in SUIT_CODES for rank in RANK_CODES
)
_CARD_LABEL_SET = frozenset(CARD_LABELS)


def parse_card_label(label: str) -> tuple[str, str]:
    """Return canonical ``(rank, suit)`` without accepting source aliases."""

    if not isinstance(label, str) or label not in _CARD_LABEL_SET:
        raise ValueError(f"Unsupported card label: {label!r}")
    return label[:-1], label[-1]


def format_card_label(rank: str, suit: str) -> str:
    """Build a canonical label, rejecting aliases instead of guessing."""

    if rank not in RANK_CODES or suit not in SUIT_CODES:
        raise ValueError(f"Unsupported rank or suit: rank={rank!r}, suit={suit!r}")
    return f"{rank}{suit}"
