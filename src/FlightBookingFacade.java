public class FlightBookingFacade {

    // Encapsulated subsystem references
    private final SeatInventorySystem seatSystem;
    private final BaggagePricingEngine baggageEngine;
    private final CurrencyConverter currencyConverter;
    private final PaymentProcessor paymentProcessor;
    private final TicketIssuer ticketIssuer;

    public FlightBookingFacade() {
        this.seatSystem = new SeatInventorySystem();
        this.baggageEngine = new BaggagePricingEngine();
        this.currencyConverter = new CurrencyConverter();
        this.paymentProcessor = new PaymentProcessor();
        this.ticketIssuer = new TicketIssuer();
    }

    /**
     * Backward-compatible entry point: delegates with the STANDARD loyalty tier.
     */
    public String bookFlight(String passengerName,
                             String flightCode,
                             String seatNumber,
                             double baseFareUSD,
                             int bagCount,
                             double bagWeightKg,
                             String currency,
                             String cardNumber) {
        return bookFlight(passengerName, flightCode, seatNumber, baseFareUSD,
                bagCount, bagWeightKg, currency, cardNumber, "STANDARD");
    }

    /**
     * Single entry point for booking a complete flight itinerary,
     * with loyalty tier handling (STANDARD, SILVER, GOLD).
     */
    public String bookFlight(String passengerName,
                             String flightCode,
                             String seatNumber,
                             double baseFareUSD,
                             int bagCount,
                             double bagWeightKg,
                             String currency,
                             String cardNumber,
                             String loyaltyTier) {

        String lockToken = null;

        try {
            // 1. Connect to seat server and lock the requested seat
            seatSystem.connectToReservationServer();
            lockToken = seatSystem.lockSeat(flightCode, seatNumber, passengerName);

            // 2. Baggage fee: SILVER and GOLD are exempt, engine is skipped entirely
            double baggageFeeUSD;
            if ("SILVER".equalsIgnoreCase(loyaltyTier) || "GOLD".equalsIgnoreCase(loyaltyTier)) {
                baggageFeeUSD = 0.0;
            } else {
                baggageFeeUSD = baggageEngine.calculateBaggageFeeUSD(bagCount, bagWeightKg);
            }
            double totalUSD = baseFareUSD + baggageFeeUSD;

            // 3. Convert total into the target currency
            double convertedTotal = currencyConverter.convertFromUSD(totalUSD, currency);

            // 4. Initialize gateway, apply promo for SILVER, then charge the card
            paymentProcessor.initializeGateway();
            if ("SILVER".equalsIgnoreCase(loyaltyTier)) {
                paymentProcessor.applyPromoCode("SILVER-FREE-BAG");
            }
            String receiptId = paymentProcessor.chargeCard(cardNumber, convertedTotal, currency);

            // 5. Generate and return the e-ticket
            return ticketIssuer.generateTicket(passengerName, flightCode, lockToken, receiptId);

        } catch (RuntimeException ex) {
            // 6. Roll back the seat lock if it was acquired
            if (lockToken != null) {
                seatSystem.releaseSeatLock(lockToken);
            }
            System.out.println("[Facade] Booking failed for " + passengerName + ": " + ex.getMessage());
            return "BOOKING_FAILED: " + ex.getMessage();

        } finally {
            // 7. Always disconnect from the seat server
            seatSystem.disconnect();
        }
    }
}