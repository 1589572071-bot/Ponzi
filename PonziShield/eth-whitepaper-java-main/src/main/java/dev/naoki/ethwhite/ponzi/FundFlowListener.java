package dev.naoki.ethwhite.ponzi;

public interface FundFlowListener {
    void onTransfer(FundFlowEvent event);
}
