# PonziShield - Part 1: Base Module (PPT Outline)

## Slide 1: Title Slide
- Title: PonziShield: Detecting Ponzi Schemes on Ethereum
- Subtitle: Part 1 - Base Module (Layer 1) Innovations
- Author: [Your Name]
- Course: Blockchain and Digital Currency - Final Project

---

## Slide 2: Project Overview
- PonziShield: full-stack Ethereum Ponzi detection framework
- 5 layers: Base -> Graph -> ML -> Lifecycle -> Risk Fusion
- My role: Layer 1 (Base Module)
- Deliverables: Dynamic rate mechanism, Typed events, Enhanced demos

---

## Slide 3: Layer 1 Architecture
- EthWhite VM: native Java Ethereum execution
- PonziContract: simulated Ponzi smart contract
- FundFlowEmitter: async POST to Python backend
- Interface: POST /api/v1/transfer (backward compatible)

---

## Slide 4: Innovation 1 - Dynamic Rate Mechanism
- Problem: Fixed rates cannot model real Ponzi behavior
- Solution: EARLY phase (high rates) -> MATURE phase (reduced rates)
- Threshold: 5 investors (configurable)
- New methods: phase(), referralBpsNow(), poolBpsNow()

---

## Slide 5: Innovation 2 - Typed Fund-Flow Events
- Problem: Cannot distinguish stake vs. withdraw vs. reward
- Solution: EventType enum (STAKE, WITHDRAW, REFERRAL_REWARD, DIVIDEND)
- FundFlowEvent carries event_type in JSON
- Python backend stores event_type on graph edges

---

## Slide 6: Innovation 3 - Enhanced Demo Scenarios
- Original: only linear referral chain
- New: NetworkDemo (3-node cycle), WhaleEarlyExitDemo
- Tests cycle detection and early-exit patterns
- Backward compatible

---

## Slide 7: Backward Compatibility
- All changes are fully backward compatible
- Existing API works without event_type
- Part 2/4/5 code: no modifications needed

---

## Slide 8: Evaluation
- Correctness: All files compile, rates transition correctly
- Expressiveness: New demos generate complex patterns
- Integration: Tested with Part 2 and Part 5 modules

---

## Slide 9: Contribution Summary
- Dynamic rate mechanism (PonziContract.java)
- Typed fund-flow events (EventType.java, etc.)
- Enhanced demo scenarios (PonziDemoMain.java)
- Full backward compatibility
- Code pushed to GitHub

---

## Slide 10: Q&A
- Thank you!
- Questions?

---

## Presenter Notes:
- Total time: 10-12 minutes
- Emphasize backward compatibility
- Show live demo if possible
- Highlight innovations enable better features for teammates
