#!/usr/bin/env bash
# Fails when Play Billing types (`com.android.billingclient`) spread outside `billing/` and the
# public boundary. Internal SDK code reads purchases and products through the store-neutral
# `Store` port (`billing/Store.kt`); Play-to-neutral conversions live in `billing/google/`.
#
# The files below keep Play types because a public signature in them needs one, or because they
# adapt such a public type to the neutral one. Add a file here only for that reason.
set -euo pipefail

root="${1:-superwall/src/main/java}"
sdk="com/superwall/sdk"

allowed=(
  # Public API: Play types in public signatures.
  "$sdk/Superwall.kt"                                                          # purchase(ProductDetails), observePurchase*(…)
  "$sdk/delegate/PurchaseResult.kt"                                            # InternalPurchaseResult.Purchased(Purchase)
  "$sdk/delegate/subscription_controller/PurchaseController.kt"                # purchase(activity, ProductDetails, …)
  "$sdk/delegate/subscription_controller/PurchaseControllerJava.kt"
  "$sdk/dependencies/FactoryProtocols.kt"                                      # StoreTransactionFactory.makeStoreTransaction(Purchase)
  "$sdk/dependencies/DependencyContainer.kt"                                   # implements makeStoreTransaction(Purchase)
  "$sdk/store/AutomaticPurchaseController.kt"                                  # public PurchaseController implementation
  "$sdk/store/InternalPurchaseController.kt"                                   # wraps the public PurchaseController
  "$sdk/store/PurchasingObserverState.kt"                                      # public observer-mode states
  "$sdk/store/abstractions/product/RawStoreProduct.kt"                         # wraps ProductDetails
  "$sdk/store/abstractions/product/receipt/PlayStorePurchaseAdapter.kt"        # public Purchase adapter
  "$sdk/store/abstractions/product/receipt/SubscriptionStatusProvider.kt"      # public provider over Purchase
  "$sdk/store/abstractions/product/receipt/StoreSubscriptionStatus.kt"         # adapts the public provider to StorePurchase
  "$sdk/store/abstractions/transactions/GoogleBillingPurchaseTransaction.kt"   # public transaction over Purchase
  "$sdk/store/abstractions/transactions/StorePayment.kt"
  "$sdk/store/abstractions/transactions/StoreTransactionType.kt"
)

hits=""
while IFS= read -r file; do
  rel="${file#"$root"/}"
  case "$rel" in
    "$sdk"/billing/*) continue ;;
  esac
  ok=false
  for a in "${allowed[@]}"; do
    if [ "$rel" = "$a" ]; then ok=true; break; fi
  done
  if [ "$ok" = false ]; then hits+="$rel"$'\n'; fi
done < <(grep -rlE 'com\.android\.billingclient' "$root" --include='*.kt' --include='*.java' || true)

if [ -n "$hits" ]; then
  echo "Play Billing types used outside billing/ and the public boundary." >&2
  echo "Read purchases and products through the Store port, or convert in billing/google/:" >&2
  printf '%s' "$hits" >&2
  exit 1
fi
echo "Play Billing types stay in billing/ and on public signatures."
