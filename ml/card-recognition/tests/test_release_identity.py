"""Release timestamp language must behave identically on the pinned Python 3.9 runtime."""

import pytest

from pontinho_cards.release_identity import validate_release_identity


@pytest.mark.parametrize("fraction", ["", *["." + "123456789"[:n] for n in range(1, 10)]])
def test_valid_fraction_lengths_preserve_original_nanosecond_timestamp(fraction: str) -> None:
    timestamp = f"2024-02-29T23:59:59{fraction}Z"
    assert validate_release_identity("cards-1", timestamp) == ("cards-1", timestamp)


@pytest.mark.parametrize("timestamp", [
    "2023-02-29T12:00:00Z", "2024-02-30T12:00:00Z", "2024-13-01T12:00:00Z",
    "2024-01-00T12:00:00Z", "2024-01-01T25:00:00Z", "2024-01-01T12:60:00Z",
    "2024-01-01T12:00:61Z", "2024-01-01T12:00:00.Z",
    "2024-01-01T12:00:00.1234567890Z", "2024-01-01T12:00:00+00:00",
    "2024-01-01T12:00:00-03:00", "2024-01-01 12:00:00Z",
    "2024-01-01T12:00:00z", "2024-01-01T12:00:00Z\n", "2024-01-01T12:00:00Zjunk",
])
def test_invalid_calendar_or_noncanonical_utc_timestamp_is_rejected(timestamp: str) -> None:
    with pytest.raises(ValueError, match="UTC RFC 3339"):
        validate_release_identity("cards-1", timestamp)
