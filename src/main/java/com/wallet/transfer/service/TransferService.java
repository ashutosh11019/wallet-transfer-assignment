package com.wallet.transfer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wallet.transfer.domain.IdempotencyRecord;
import com.wallet.transfer.domain.LedgerEntry;
import com.wallet.transfer.domain.LedgerEntryType;
import com.wallet.transfer.domain.Transfer;
import com.wallet.transfer.domain.TransferState;
import com.wallet.transfer.domain.Wallet;
import com.wallet.transfer.dto.CreateTransferRequest;
import com.wallet.transfer.dto.TransferResponse;
import com.wallet.transfer.exception.IdempotencyConflictException;
import com.wallet.transfer.exception.InsufficientFundsException;
import com.wallet.transfer.exception.WalletNotFoundException;
import com.wallet.transfer.repository.IdempotencyRecordRepository;
import com.wallet.transfer.repository.LedgerEntryRepository;
import com.wallet.transfer.repository.TransferRepository;
import com.wallet.transfer.repository.WalletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
public class TransferService {

    private final WalletRepository walletRepository;
    private final TransferRepository transferRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final ObjectMapper objectMapper;

    public TransferService(WalletRepository walletRepository,
                           TransferRepository transferRepository,
                           LedgerEntryRepository ledgerEntryRepository,
                           IdempotencyRecordRepository idempotencyRecordRepository,
                           ObjectMapper objectMapper) {
        this.walletRepository = walletRepository;
        this.transferRepository = transferRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(noRollbackFor = InsufficientFundsException.class)
    public TransferResponse createTransfer(CreateTransferRequest request) {
        String key = request.idempotencyKey();
        boolean hasIdempotencyKey = key != null && !key.trim().isEmpty();

        if (hasIdempotencyKey) {
            Optional<IdempotencyRecord> existing = idempotencyRecordRepository.findById(key);
            if (existing.isPresent()) {
                IdempotencyRecord record = existing.get();
                String hash = calculateRequestHash(request);
                if (!record.getRequestHash().equals(hash)) {
                    throw new IdempotencyConflictException("Idempotency key has been used with different request parameters");
                }
                if (record.getResponseStatus() != null) {
                    if (record.getResponseStatus() == 200) {
                        try {
                            return objectMapper.readValue(record.getResponseBody(), TransferResponse.class);
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to deserialize idempotent response", e);
                        }
                    } else {
                        throw new InsufficientFundsException(record.getResponseBody());
                    }
                } else {
                    throw new IdempotencyConflictException("Request with this idempotency key is already in progress");
                }
            }

            String hash = calculateRequestHash(request);
            IdempotencyRecord record = new IdempotencyRecord(
                    key,
                    hash,
                    null,
                    null,
                    LocalDateTime.now()
            );
            try {
                idempotencyRecordRepository.saveAndFlush(record);
            } catch (org.springframework.dao.DataIntegrityViolationException ex) {
                IdempotencyRecord concurrentRecord = idempotencyRecordRepository.findById(key)
                        .orElseThrow(() -> new IdempotencyConflictException("Request is being processed by another thread"));
                if (concurrentRecord.getResponseStatus() != null) {
                    if (concurrentRecord.getResponseStatus() == 200) {
                        try {
                            return objectMapper.readValue(concurrentRecord.getResponseBody(), TransferResponse.class);
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to deserialize idempotent response", e);
                        }
                    } else {
                        throw new InsufficientFundsException(concurrentRecord.getResponseBody());
                    }
                } else {
                    throw new IdempotencyConflictException("Request with this idempotency key is already in progress");
                }
            }
        }


        String fromId = request.fromWalletId();
        String toId = request.toWalletId();
        if (fromId.equals(toId)) {
            throw new IllegalArgumentException("Cannot transfer to the same wallet");
        }


        Wallet firstWallet;
        Wallet secondWallet;
        if (fromId.compareTo(toId) < 0) {
            firstWallet = walletRepository.findByIdForUpdate(fromId)
                    .orElseThrow(() -> new WalletNotFoundException("Source wallet not found: " + fromId));
            secondWallet = walletRepository.findByIdForUpdate(toId)
                    .orElseThrow(() -> new WalletNotFoundException("Destination wallet not found: " + toId));
        } else {
            secondWallet = walletRepository.findByIdForUpdate(toId)
                    .orElseThrow(() -> new WalletNotFoundException("Destination wallet not found: " + toId));
            firstWallet = walletRepository.findByIdForUpdate(fromId)
                    .orElseThrow(() -> new WalletNotFoundException("Source wallet not found: " + fromId));
        }

        Wallet fromWallet = fromId.equals(firstWallet.getId()) ? firstWallet : secondWallet;
        Wallet toWallet = toId.equals(firstWallet.getId()) ? firstWallet : secondWallet;

        String transferId = "T-" + UUID.randomUUID().toString();
        Transfer transfer = new Transfer(
                transferId,
                fromId,
                toId,
                request.amount(),
                TransferState.PENDING,
                key,
                LocalDateTime.now()
        );
        transferRepository.save(transfer);

        if (fromWallet.getBalance() < request.amount()) {
            transfer.setState(TransferState.FAILED);
            transferRepository.save(transfer);

            String errMsg = "Insufficient funds in wallet: " + fromId;
            if (hasIdempotencyKey) {
                saveIdempotencyResponse(key, 400, errMsg);
            }
            throw new InsufficientFundsException(errMsg);
        }

        fromWallet.setBalance(fromWallet.getBalance() - request.amount());
        toWallet.setBalance(toWallet.getBalance() + request.amount());
        walletRepository.save(fromWallet);
        walletRepository.save(toWallet);

        LedgerEntry debitEntry = new LedgerEntry(
                "LE-" + UUID.randomUUID().toString(),
                fromId,
                transferId,
                LedgerEntryType.DEBIT,
                request.amount(),
                LocalDateTime.now()
        );
        LedgerEntry creditEntry = new LedgerEntry(
                "LE-" + UUID.randomUUID().toString(),
                toId,
                transferId,
                LedgerEntryType.CREDIT,
                request.amount(),
                LocalDateTime.now()
        );
        ledgerEntryRepository.save(debitEntry);
        ledgerEntryRepository.save(creditEntry);

        transfer.setState(TransferState.PROCESSED);
        transferRepository.save(transfer);

        TransferResponse response = new TransferResponse(
                transferId,
                fromId,
                toId,
                request.amount(),
                TransferState.PROCESSED,
                transfer.getCreatedAt()
        );

        if (hasIdempotencyKey) {
            try {
                String jsonResponse = objectMapper.writeValueAsString(response);
                saveIdempotencyResponse(key, 200, jsonResponse);
            } catch (Exception e) {
                throw new RuntimeException("Failed to serialize transfer response for idempotency", e);
            }
        }

        return response;
    }

    private String calculateRequestHash(CreateTransferRequest request) {
        return request.fromWalletId() + "|" + request.toWalletId() + "|" + request.amount();
    }

    private void saveIdempotencyResponse(String key, int status, String body) {
        IdempotencyRecord record = idempotencyRecordRepository.findById(key)
                .orElseThrow(() -> new IllegalStateException("Idempotency record not found during update"));
        record.setResponseStatus(status);
        record.setResponseBody(body);
        idempotencyRecordRepository.save(record);
    }
}
