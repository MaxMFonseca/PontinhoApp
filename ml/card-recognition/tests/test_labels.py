import json

import pytest

from pontinho_cards.labels import (
    CARD_LABELS,
    RANK_CODES,
    SUIT_CODES,
    format_card_label,
    parse_card_label,
)


def test_card_labels_have_stable_suit_then_rank_order() -> None:
    expected_ranks = ("A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K")
    expected = tuple(f"{rank}{suit}" for suit in ("C", "D", "H", "S") for rank in expected_ranks)

    assert RANK_CODES == expected_ranks
    assert SUIT_CODES == ("C", "D", "H", "S")
    assert CARD_LABELS == expected
    assert len(CARD_LABELS) == 52
    assert len(set(CARD_LABELS)) == 52
    assert json.dumps(CARD_LABELS, separators=(",", ":")) == json.dumps(
        expected, separators=(",", ":")
    )


@pytest.mark.parametrize("label", CARD_LABELS)
def test_rank_and_suit_conversion_is_reversible(label: str) -> None:
    rank, suit = parse_card_label(label)

    assert format_card_label(rank, suit) == label


@pytest.mark.parametrize(
    "label",
    ["joker", "JOKER", "back", "card_back", "1H", "ace_hearts", "ah", "AH ", "", "11S"],
)
def test_unsupported_or_ambiguous_label_is_rejected(label: str) -> None:
    with pytest.raises(ValueError, match="Unsupported card label"):
        parse_card_label(label)


@pytest.mark.parametrize(
    ("rank", "suit"),
    [("ACE", "H"), ("A", "HEARTS"), ("1", "H"), ("A", "R"), ("", "S")],
)
def test_unknown_rank_or_suit_alias_is_rejected(rank: str, suit: str) -> None:
    with pytest.raises(ValueError, match="Unsupported rank or suit"):
        format_card_label(rank, suit)
