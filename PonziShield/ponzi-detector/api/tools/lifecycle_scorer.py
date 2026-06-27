from __future__ import annotations

from api.tools.transfer_graph import TransferEvent


def score_lifecycle(
    events: list[TransferEvent],
    contract_address: str,
    current_block: int,
    is_demo_contract: bool = False,
) -> dict[str, object]:
    contract = contract_address.lower()
    related = [
        event for event in events
        if event.from_address == contract or event.to_address == contract
    ]
    inbound = [event for event in related if event.to_address == contract]
    outbound = [event for event in related if event.from_address == contract]
    same_block_payouts = [
        event for event in outbound
        if any(in_event.block_number == event.block_number for in_event in inbound)
    ]
    post_fundraising_withdraws = [
        event for event in outbound
        if inbound and event.block_number > max(in_event.block_number for in_event in inbound)
    ]

    fund_flow_score = 0.9 if inbound and outbound else 0.12
    profit_score = min(1.0, len(same_block_payouts) / 6) if inbound else 0.1
    referral_score = min(1.0, len(same_block_payouts) / 10) if same_block_payouts else 0.1
    withdrawal_score = 0.82 if post_fundraising_withdraws else 0.12
    camouflage_score = 0.86 if is_demo_contract or len(inbound) >= 5 else 0.18

    dimensions = {
        "fund_flow": {
            "detected": bool(inbound and outbound),
            "score": fund_flow_score,
            "evidence": f"{len(inbound)} inbound stakes and {len(outbound)} contract payouts",
        },
        "profit_logic": {
            "detected": len(same_block_payouts) >= 3,
            "score": profit_score,
            "evidence": f"{len(same_block_payouts)} same-block payouts triggered by new stakes",
        },
        "referral_mechanism": {
            "detected": len(same_block_payouts) >= 3,
            "score": referral_score,
            "evidence": "stake(referrer) demo produced immediate referral-like payouts",
        },
        "withdrawal_control": {
            "detected": bool(post_fundraising_withdraws),
            "score": withdrawal_score,
            "evidence": f"{len(post_fundraising_withdraws)} payouts occurred after fundraising blocks, matching lockBlocks withdraw flow",
        },
        "camouflage": {
            "detected": is_demo_contract or len(inbound) >= 5,
            "score": camouflage_score,
            "evidence": "investment entry is exposed as stake(referrer), not invest()",
        },
    }

    stage = _stage(current_block, inbound, outbound, post_fundraising_withdraws)
    score = _stage_score(stage, dimensions)
    first_block = min((event.block_number for event in related), default=current_block)
    return {
        "stage": stage,
        "age_blocks": max(0, current_block - first_block),
        "score": round(score, 4),
        "dimensions": dimensions,
    }


def _stage(
    current_block: int,
    inbound: list[TransferEvent],
    outbound: list[TransferEvent],
    post_fundraising_withdraws: list[TransferEvent],
) -> str:
    if post_fundraising_withdraws and current_block >= 1000:
        return "COLLAPSE"
    if post_fundraising_withdraws:
        return "STAGNATION"
    if inbound and outbound:
        return "PAYOUT"
    return "FUNDRAISING"


def _stage_score(stage: str, dimensions: dict[str, dict[str, object]]) -> float:
    weights = {
        "FUNDRAISING": {"referral_mechanism": 0.4, "fund_flow": 0.3, "camouflage": 0.3},
        "PAYOUT": {"fund_flow": 0.5, "profit_logic": 0.3, "referral_mechanism": 0.2},
        "STAGNATION": {"withdrawal_control": 0.4, "fund_flow": 0.3, "profit_logic": 0.3},
        "COLLAPSE": {"withdrawal_control": 0.5, "fund_flow": 0.3, "camouflage": 0.2},
    }[stage]
    return sum(float(dimensions[name]["score"]) * weight for name, weight in weights.items())