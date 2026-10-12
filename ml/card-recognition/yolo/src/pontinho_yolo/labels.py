"""Canonical playing-card labels in detector output order."""

from __future__ import annotations

CARD_LABELS: tuple[str, ...] = (
    "10C",
    "10D",
    "10H",
    "10S",
    "2C",
    "2D",
    "2H",
    "2S",
    "3C",
    "3D",
    "3H",
    "3S",
    "4C",
    "4D",
    "4H",
    "4S",
    "5C",
    "5D",
    "5H",
    "5S",
    "6C",
    "6D",
    "6H",
    "6S",
    "7C",
    "7D",
    "7H",
    "7S",
    "8C",
    "8D",
    "8H",
    "8S",
    "9C",
    "9D",
    "9H",
    "9S",
    "AC",
    "AD",
    "AH",
    "AS",
    "JC",
    "JD",
    "JH",
    "JS",
    "KC",
    "KD",
    "KH",
    "KS",
    "QC",
    "QD",
    "QH",
    "QS",
)

_RANK_NAMES = {
    "A": "ACE",
    "2": "TWO",
    "3": "THREE",
    "4": "FOUR",
    "5": "FIVE",
    "6": "SIX",
    "7": "SEVEN",
    "8": "EIGHT",
    "9": "NINE",
    "10": "TEN",
    "J": "JACK",
    "Q": "QUEEN",
    "K": "KING",
}
_SUIT_NAMES = {"C": "CLUBS", "D": "DIAMONDS", "H": "HEARTS", "S": "SPADES"}
_CARD_LABEL_SET = frozenset(CARD_LABELS)


def card_label_to_rank_suit(label: str) -> tuple[str, str]:
    """Map one detector label to the corresponding Pontinho rank and suit names."""
    if not isinstance(label, str) or label not in _CARD_LABEL_SET:
        raise ValueError(f"unsupported card label: {label!r}")
    return _RANK_NAMES[label[:-1]], _SUIT_NAMES[label[-1]]
