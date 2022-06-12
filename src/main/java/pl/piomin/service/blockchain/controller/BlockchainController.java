package pl.piomin.service.blockchain.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.web3j.protocol.core.methods.response.EthBlockNumber;
import pl.piomin.service.blockchain.model.BlockchainTransaction;
import pl.piomin.service.blockchain.service.BlockchainService;

import java.io.IOException;

@RestController
public class BlockchainController {

    private final BlockchainService service;

    public BlockchainController(BlockchainService service) {
        this.service = service;
    }

    @PostMapping("/transaction")
    public BlockchainTransaction execute(@RequestBody BlockchainTransaction transaction) throws IOException {
        return service.process(transaction);
    }

    @GetMapping("height")
    public long getHeight() {
        try {
            return service.getHeight();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 0;
    }

}
