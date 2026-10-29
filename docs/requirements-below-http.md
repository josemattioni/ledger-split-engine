# Requirements the contract suite cannot reach over HTTP

The contract suite (`src/contractTest`) sees the service the way a client does: HTTP and JSON. Some requirements are about
what a client cannot see: the database's privileges and triggers, the application refusing to start, a log line, a failure
that a valid request cannot cause. The suite used to carry a placeholder for each, skipped with `@Disabled` (29 of them: the
"skipped" in every build's output), so that every scenario of the specs had a line in it. They were removed, because a
skipped test reads as decay, and this table replaces them: for each requirement it keeps the placeholder's former name
(so the history can be followed), and says which test in `src/test` covers it. Every test named here exists and was read;
none is a stand-in.

When a spec scenario cannot be tested through the API, add a row here with the test that covers it, and a test if there is
none: a requirement with no test is the gap this table exists to show. Closing the last one found this way
(`BackstopFailureIsLoggedTest`: the specs say a backstop failure is logged at error level, which nothing observed) is part
of this document.

## The database refuses what the application identity and the owner may not do

| Former contract placeholder | Requirement (spec) | Covered by (`src/test`) |
|---|---|---|
| `ImmutableAccountsContractTest.appIdentityCannotUpdate`, `LedgerAppendOnlyContractTest.appIdentityCannotMutate` | `app-identity-read-insert-only`: an update of accounts, journal entries or entry lines by the application identity is a permission denial | `LedgerAppPrivilegesTest.updateIsRefusedWithInsufficientPrivilege` (run for each of the three tables) |
| `ImmutableAccountsContractTest.appIdentityCannotDelete` | the same, for a delete | `LedgerAppPrivilegesTest.deleteIsRefusedWithInsufficientPrivilege` (three tables) |
| `ImmutableAccountsContractTest.appIdentityCannotTruncate` | the same, for a truncate | `LedgerAppPrivilegesTest.truncateIsRefusedWithInsufficientPrivilege` (three tables) |
| `LedgerAppendOnlyContractTest.schemaChangesByOwner` | `schema-changes-by-owner`: migrations are recorded as applied by the owner, and the application identity cannot create, alter or drop | `LedgerAppPrivilegesTest.migrationsWereAppliedByTheOwnerNotTheApplicationRole`, `theApplicationRoleCannotChangeTheSchemaOrDisableTriggers` |
| `ImmutableAccountsContractTest.ownerCannotUpdateAccount` | `ledger-rows-immutable-even-for-owner`: the owner's update of an account, with or without lines, is refused as append-only | `AccountsImmutableTest.ownerUpdateOfAnAccountThatHasLinesIsRefused`, `ownerUpdateOfAnAccountThatHasNoLinesIsRefused` |
| `ImmutableAccountsContractTest.ownerCannotDeleteAccount` | the owner's delete of an account, with or without lines | `AccountsImmutableTest.ownerDeleteOfAnAccountThatHasLinesIsRefused`, `ownerDeleteOfAnAccountThatHasNoLinesIsRefused` |
| `ImmutableAccountsContractTest.ownerCannotTruncateAccounts` | the owner's truncate of accounts together with the tables that reference it, or cascading | `AccountsImmutableTest.ownerTruncateOfAccountsWithCascadeIsRefusedByTheAccountsTrigger`, `ownerTruncateOfAccountsListedBeforeEntryLinesIsRefusedByTheAccountsTrigger` |
| `ImmutableAccountsContractTest.ownerCannotTruncateReferencedAccountsAlone` | the owner's truncate of accounts alone, while entry lines reference it, is refused | `AccountsImmutableTest.aBareTruncateOfAccountsIsStoppedByTheForeignKeyBeforeTheTriggerIsReached` |
| `LedgerAppendOnlyContractTest.ownerCannotMutateLedger` | the owner's update, delete or truncate of journal entries or entry lines | `AppendOnlyTriggersTest.ownerUpdateOfAnExistingRowIsRefused`, `ownerDeleteOfAnExistingRowIsRefused`, `ownerTruncateOfEntryLinesIsRefused`, `ownerTruncateOfBothTablesTogetherIsRefused`, `ownerTruncateOfJournalEntriesWithCascadeIsRefused` |
| `ImmutableAccountsContractTest.appIdentityCannotSupplyServerColumn` | `server-assigned-from-defaults`: an insert naming a server-assigned column is a permission denial and writes nothing | `ServerAssignedColumnsTest.theApplicationCannotSupplyAnAccountsCurrency`, `theApplicationCannotSupplyAnAccountsCreatedAt`, `theApplicationCannotSupplyAJournalEntrysEntryId`, `theApplicationCannotSupplyAJournalEntrysCreatedAt` |
| `ImmutableAccountsContractTest.appIdentityCannotOverrideIdentity` | overriding a generated identity is a permission denial and writes nothing | `ServerAssignedColumnsTest.theApplicationCannotOverrideAnAccountsGeneratedId`, `theApplicationCannotOverrideAJournalEntrysGeneratedId`, `theApplicationCannotOverrideAnEntryLinesGeneratedId` |
| `ImmutableAccountsContractTest.insertPrivilegesAreExactlyClientColumns` | the application identity holds `INSERT` on exactly the client-supplied columns | `ServerAssignedColumnsTest.theInsertPrivilegesAreExactlyTheColumnsAClientSupplies` |
| `ImmutableAccountsContractTest.laterTableReadOnly` | `later-tables-read-only-until-granted`: a table created later is readable but not writable | `LedgerAppPrivilegesTest.aTableCreatedLaterByTheOwnerIsReadableButNotWritableForTheApplicationRole` |
| `ImmutableAccountsContractTest.laterTableWritableOnlyOnGrantedColumns` | after a column-level grant, only those columns are writable | `ServerAssignedColumnsTest.aLaterTableIsWritableOnlyThroughAnExplicitColumnLevelGrant` |

## The application refuses to start, and a transaction cannot outlive its bound

| Former contract placeholder | Requirement (spec) | Covered by (`src/test`) |
|---|---|---|
| `AsOfBalanceContractTest.transactionPastBoundNeverCommits` | `settled-answer-final`: a transaction of the application identity that outlives its bound is terminated and none of its rows exist | `TransactionTimeoutTest.aTransactionLeftIdlePastItsBoundNeverCommits` (and `aStatementWaitingOnAnotherTransactionsLockIsTerminatedByItsBoundWhileTheOtherStillHoldsIt`) |
| `AsOfBalanceContractTest.startupRefusedWithoutBound` | startup is refused, saying the bound is missing, when the application identity has none | `SettleWindowTest.anAbsentBoundIsRefusedWithAMessageNamingPostgres17`, `aBoundOfZeroIsRefusedAsDisabled`; `TransactionBoundGuardTest.aGuardOverTheOwnersUnboundedSessionIsRefusedAsDisabledWithTheRecreateTheVolumeHint` |
| `AsOfBalanceContractTest.startupRefusedWithLongBound` | startup is refused, stating the largest accepted bound, when the bound exceeds 30 seconds | `SettleWindowTest.aBoundOneMillisecondOverThirtySecondsIsRefusedStatingTheMaximum` |
| `AsOfBalanceContractTest.startupRefusedWithPreparedTransactions` | startup is refused, naming the setting, when prepared transactions are enabled | `TransactionBoundGuardTest.aServerThatAllowsPreparedTransactionsIsRefusedNamingTheSettingEvenWithAGoodBound`; `SettleWindowTest.preparedTransactionsAreRefusedWhateverTheBound` |

## A server error leaves nothing behind, and is logged

| Former contract placeholder | Requirement (spec) | Covered by (`src/test`) |
|---|---|---|
| `IdempotentReplayContractTest.serverErrorLeavesKeyUnused` | `only-success-consumes-key`: a post that fails with a server error leaves its key unused | `ServerErrorLeavesKeyUnusedIntegrationTest.aPostThatFailsWhileInsertingItsLinesLeavesItsKeyUnused`, `theKeyOfAFailedPostCanBeUsedForADifferentRequest` |
| `JournalEntryReversalContractTest.serverErrorLeavesKeyUnused` | `rejected-reversal-keeps-key`: a reversal that fails with a server error leaves its key unused | `ServerErrorLeavesKeyUnusedIntegrationTest.aReversalThatFailsWhileInsertingItsLinesLeavesItsKeyUnusedAndTheEntryUnreversed` |
| `JournalEntryContractTest.backstopFailureIsGeneric500`, `JournalEntryGuardsContractTest.entryShapeBackstopIs500` | `storage-backstop-is-internal-error`: an entry-shape backstop failure is a `500 problem+json` with a generic detail, **and is logged** | The `500`: `JournalEntryControllerTest.aCommitTimeBackstopFailureReturns500`, `aCheckViolationFromTheStorageLayerReturns500`, `aUniqueViolationThatIsNotTheIdempotencyKeyReturns500NotA409`. The log: `BackstopFailureIsLoggedTest.anEntryShapeBackstopFailureIsAnswered500AndLoggedAtErrorLevel`. The storage side that fires: `DegenerateEntryBackstopsTest`, `EntryLinesBalanceTriggerTest.unbalancedLinesCommitFailsWithLdg01` |
| `JournalEntryReversalContractTest.reversalBackstopIsGeneric500` | `backstop-internal-error`: a reversal refused by the storage layer's own safeguard is a `500 problem+json`, generic, **and logged** | The log and the `500`: `BackstopFailureIsLoggedTest.aReversalBackstopFailureIsAnswered500AndLoggedAtErrorLevel`. The storage side that fires: `ReversalConstraintsTest.aReversalWhoseLinesAreNotNegatedFailsAtCommitWithLdg04`. The use case's own failure as a `500`: `JournalEntryControllerTest.anIllegalArgumentExceptionFromTheReversalUseCaseIs500NotA400` |

## Idempotency and concurrency that need a failure or a change an API cannot make

| Former contract placeholder | Requirement (spec) | Covered by (`src/test`) |
|---|---|---|
| `IdempotentReplayContractTest.retryIndependentOfAccountState` | `idempotency-check-order`: a retry is answered from the stored entry without depending on the current state of its accounts | `IdempotentInsertOrFindTest.precedence_anExistingKeyIsFoundBeforeTheRequestsAccountsAreResolved`; `PostJournalEntryTest.aRetryWithTheSameLinesIsReplayedAsTheStoredEntryAndWritesNothing` |
| `JournalEntryReversalContractTest.waitingRequestProceedsAfterFailure` | `concurrent-reversals-one`: if the first of several simultaneous requests fails and leaves no reversal, a waiting request proceeds as a new request | `ReversalConcurrencyTest.aWaitingReversalProceedsAndReturnsARowWhenTheFirstTransactionRollsBack` |

## Currency and amounts that the one-currency engine and the amount limit make unreachable

| Former contract placeholder | Requirement (spec) | Covered by (`src/test`) |
|---|---|---|
| `JournalEntryContractTest.mixedCurrencyRejected`, `JournalEntryGuardsContractTest.mixedCurrencyType` | `lines-share-one-currency`, `ledger-rule-problem-types`: lines on accounts of more than one currency are a `422` of type `mixed-currency`, nothing created | `JdbcJournalEntryAdapterTest.validateSingleCurrencyThrowsWhenAccountsSpanMultipleCurrencies`; `JournalEntryControllerTest.mixedCurrencyReturns422ProblemJson` (it asserts the problem type). Unreachable over HTTP today because every account is BRL |
| `AccountBalanceContractTest.balanceBeyond64Bits` | `read-derived-balance`: a sum beyond 64 bits is reported exactly or refused, never wrapped | Unreachable: a line is capped at 10^12 centavos, so one account would take about 9.2 million posts. The cap that makes it so: `JournalEntryTest.acceptsAmountsExactlyAtTheLimit`, `rejectsAPositiveAmountOneOverTheLimitEvenWhenTheEntryBalances`, `rejectsANegativeAmountOneOverTheLimit`, `rejectsLongMinValueInsteadOfOverflowingAnAbsoluteValueCheck`; and the database's own sum beyond 64 bits, `EntryLinesBalanceTriggerTest.overflowingSumCommitFailsWithLdg01NotNumericOutOfRange`. **Reading such a balance is not tested anywhere.** |
