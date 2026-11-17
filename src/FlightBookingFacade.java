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
     * Single entry point for booking a complete flight itinerary.
     * Coordinates seat locking, baggage pricing, currency conversion,
     * payment execution, rollback on failure, and ticket issuance.
     */
    public String bookFlight(String passengerName,
                             String flightCode,
                             String seatNumber,
                             double baseFareUSD,
                             int bagCount,
                             double bagWeightKg,
                             String currency,
                             String cardNumber) {

        String lockToken = null;

        try {
            // 1. Connect to seat server and lock the requested seat
            seatSystem.connectToReservationServer();
            lockToken = seatSystem.lockSeat(flightCode, seatNumber, passengerName);

            // 2. Compute baggage fee in USD and add to base fare
            double baggageFeeUSD = baggageEngine.calculateBaggageFeeUSD(bagCount, bagWeightKg);
            double totalUSD = baseFareUSD + baggageFeeUSD;

            // 3. Convert total into the target currency
            double convertedTotal = currencyConverter.convertFromUSD(totalUSD, currency);

            // 4. Initialize payment gateway and charge the card
            paymentProcessor.initializeGateway();
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