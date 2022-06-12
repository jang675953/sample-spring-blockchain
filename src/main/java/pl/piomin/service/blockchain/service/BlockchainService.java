package pl.piomin.service.blockchain.service;

import com.alibaba.fastjson.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.*;
import org.web3j.tx.ChainId;
import org.web3j.utils.Convert;
import org.web3j.utils.Numeric;
import pl.piomin.service.blockchain.model.BlockchainTransaction;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.web3j.crypto.TransactionEncoder.*;

@Service
public class BlockchainService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BlockchainService.class);

    private final Web3j web3j;

    public BlockchainService(Web3j web3j) {
        this.web3j = web3j;
    }

    public BlockchainTransaction process(BlockchainTransaction trx) throws IOException {

        EthAccounts accounts = web3j.ethAccounts().send();
        EthGetTransactionCount transactionCount = web3j.ethGetTransactionCount(accounts.getAccounts().get(trx.getFromId()), DefaultBlockParameterName.LATEST).send();

        Transaction transaction = Transaction.createEtherTransaction(
                accounts.getAccounts().get(trx.getFromId()), transactionCount.getTransactionCount(), BigInteger.valueOf(trx.getValue()),
                BigInteger.valueOf(21_000), accounts.getAccounts().get(trx.getToId()), BigInteger.valueOf(trx.getValue()));

        EthSendTransaction response = web3j.ethSendTransaction(transaction).send();

        if (response.getError() != null) {
            trx.setAccepted(false);
            LOGGER.info("Tx rejected: {}", response.getError().getMessage());
            return trx;
        }

        trx.setAccepted(true);
        String txHash = response.getTransactionHash();
        LOGGER.info("Tx hash: {}", txHash);

        trx.setId(txHash);
        EthGetTransactionReceipt receipt = web3j.ethGetTransactionReceipt(txHash).send();

        receipt.getTransactionReceipt().ifPresent(transactionReceipt -> LOGGER.info("Tx receipt:  {}", transactionReceipt.getCumulativeGasUsed().intValue()));

        return trx;

    }

    public long getHeight() throws IOException {
        EthBlockNumber blockNumber = web3j.ethBlockNumber().send();
        long blockHeight = blockNumber.getBlockNumber().longValue();
        return blockHeight;
    }

    /**
     * 获取账户的Nonce
     *
     * @param addr
     * @return
     */
    public BigInteger getNonce(String addr) {
        try {
            EthGetTransactionCount getNonce = web3j.ethGetTransactionCount(addr, DefaultBlockParameterName.PENDING).send();

            if (getNonce == null) {
                throw new RuntimeException("net error");
            }
            return getNonce.getTransactionCount();
        } catch (IOException e) {
            throw new RuntimeException("net error");
        }
    }

    /**
     * @param fromAddress
     * @param contractAddress
     * @return
     */
    public BigInteger getTokenBalance(String fromAddress, String contractAddress) {

        String methodName = "balanceOf";
        List<Type> inputParameters = new ArrayList<>();
        List<TypeReference<?>> outputParameters = new ArrayList<>();
        Address address = new Address(fromAddress);
        inputParameters.add(address);

        TypeReference<Uint256> typeReference = new TypeReference<Uint256>() {
        };
        outputParameters.add(typeReference);
        Function function = new Function(methodName, inputParameters, outputParameters);
        String data = FunctionEncoder.encode(function);
        Transaction transaction = Transaction.createEthCallTransaction(fromAddress, contractAddress, data);

        EthCall ethCall;
        BigInteger balanceValue = BigInteger.ZERO;
        try {
            ethCall = web3j.ethCall(transaction, DefaultBlockParameterName.LATEST).send();
            List<Type> results = FunctionReturnDecoder.decode(ethCall.getValue(), function.getOutputParameters());
            balanceValue = (BigInteger) results.get(0).getValue();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return balanceValue;
    }

    /**
     * 转账ETH
     *
     * @param web3j
     * @param fromAddr
     * @param privateKey
     * @param toAddr
     * @param amount
     * @param data
     * @return
     */
    public String transferETH(String fromAddr, String privateKey, String toAddr, BigDecimal amount, String data) {
        // 获得nonce
        BigInteger nonce = getNonce(fromAddr);
        // value 转换
        BigInteger value = Convert.toWei(amount, Convert.Unit.ETHER).toBigInteger();
        //todo gasPrice
        // 构建交易
        Transaction transaction = Transaction.createEtherTransaction(fromAddr, nonce, null, null, toAddr, value);
        // 计算gasLimit
        BigInteger gasLimit = getTransactionGasLimit(transaction);

        // 查询调用者余额，检测余额是否充足
        BigDecimal ethBalance = getBalance(fromAddr);
        BigDecimal balance = Convert.toWei(ethBalance, Convert.Unit.ETHER);
        // balance < amount   gasLimit ??
        if (balance.compareTo(amount.add(new BigDecimal(gasLimit.toString()))) < 0) {
            throw new RuntimeException("余额不足，请核实");
        }
        //todo gasPrice chainId
        byte chainId = 0;
        return signAndSend(nonce, null, gasLimit, toAddr, value, data, chainId, privateKey);
    }

    private BigDecimal getBalance(String fromAddr) {
        //todo
        return null;
    }

    private BigInteger getTransactionGasLimit(Transaction transaction) {
        //todo
        return null;
    }

    /**
     * 对交易签名，并发送交易
     *
     * @param nonce
     * @param gasPrice
     * @param gasLimit
     * @param to
     * @param value
     * @param data
     * @param chainId
     * @param privateKey
     * @return
     */
    public String signAndSend(BigInteger nonce, BigInteger gasPrice, BigInteger gasLimit, String to, BigInteger value, String data, byte chainId, String privateKey) {
        String txHash = "";
        RawTransaction rawTransaction = RawTransaction.createTransaction(nonce, gasPrice, gasLimit, to, value, data);
        if (privateKey.startsWith("0x")) {
            privateKey = privateKey.substring(2);
        }

        ECKeyPair ecKeyPair = ECKeyPair.create(new BigInteger(privateKey, 16));
        Credentials credentials = Credentials.create(ecKeyPair);

        byte[] signMessage;
        if (chainId > ChainId.NONE) {
            signMessage = signMessage(rawTransaction, chainId, credentials);
        } else {
            signMessage = signMessage(rawTransaction, credentials);
        }

        String signData = Numeric.toHexString(signMessage);
        if (!"".equals(signData)) {
            try {
                EthSendTransaction send = web3j.ethSendRawTransaction(signData).send();
                txHash = send.getTransactionHash();
                System.out.println(JSON.toJSONString(send));
            } catch (IOException e) {
                throw new RuntimeException("交易异常");
            }
        }
        return txHash;
    }

}
