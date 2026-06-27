package dev.naoki.ethwhite.sample;

import dev.naoki.ethwhite.contract.ContractRegistry;

public final class ContractCatalog {
    private ContractCatalog() {
    }

    public static ContractRegistry standardRegistry() {
        return new ContractRegistry()
                .register("token", new TokenContract())
                .register("priceFeed", new PriceFeedContract())
                .register("hedge", new HedgeContract())
                .register("nameRegistry", new NameRegistryContract())
                .register("fileStorage", new FileStorageContract())
                .register("dao", new DaoContract())
                .register("ponzi", new PonziContract());
    }
}
