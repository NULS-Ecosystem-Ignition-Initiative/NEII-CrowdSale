import ch.qos.logback.core.util.COWArrayList;
import com.fasterxml.jackson.databind.BeanProperty;
import io.nuls.contract.sdk.*;
import io.nuls.contract.sdk.annotation.*;
import io.nuls.contract.sdk.event.DebugEvent;
import org.checkerframework.checker.units.qual.A;

import javax.validation.constraints.Min;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import reentrancyguard.ReentrancyGuard;

import static io.nuls.contract.sdk.Utils.emit;
import static io.nuls.contract.sdk.Utils.require;


/**
* @notice Nuls Contract that locks the nuls deposited, returns
* yield to a project during an x period of time and
* returns nuls locked in the end of the period
*
* @dev Nuls are deposited in AINULS in order to receive yield
*
* Developed by Pedro G. S. Ferreira @Pedro_Ferreir_a
* */
public class CrowdSale extends ReentrancyGuard implements Contract{

    /** 100 NULS
     *   @dev Min required to deposit in aiNULS is 100 NULS
     */
    private static final BigInteger ONE_NULS     = BigInteger.valueOf(100000000L);
    private static final BigInteger BASIS_POINTS = BigInteger.valueOf(10000);

    public Address depositCtr;
    public Address treasury; // Address that will receive the project NULS
    public Address token; // Project Token

    public Boolean paused;

    public boolean init;

    public BigInteger priceInNuls;
    public BigInteger raised;
    public BigInteger toRaiseNuls;
    public BigInteger projectShareFromRaised;

    //User Balance
    public Map<Address, BigInteger> userBalance  = new HashMap<>();
    public Map<Address, Boolean>    projectAdmin = new HashMap<>();

    //--------------------------------------------------------------------
    //Initialize Contract
    public CrowdSale(@Required Address aiNULSDepositContract_,
                     @Required Address treasury_,
                     @Required Address admin_,
                     @Required BigInteger priceInNULS_,
                     @Required BigInteger toRaiseNULS_,
                     @Required BigInteger projectShareFromRaised_
    ) {
        require(projectShareFromRaised_.compareTo(BASIS_POINTS) < 0, "Amount shared should be lower than all raised");

        treasury    = treasury_;
        paused      = false;
        priceInNuls = priceInNULS_;
        raised      = BigInteger.ZERO;
        toRaiseNuls = toRaiseNULS_;
        depositCtr  = aiNULSDepositContract_;
        projectShareFromRaised = projectShareFromRaised_;
        projectAdmin.put(admin_, true);
        init = false;

     }

    public void initialize(@Required String name,
                           @Required String symbol,
                           @Required BigInteger initialAmount,
                           @Required int decimals){
        require(projectAdmin.get(Msg.sender())!= null && projectAdmin.get(Msg.sender()), "Not Admin");
        require(!init, "Already initialized");
        String preToken = Utils.deploy(new String[]{ "token" + BigInteger.valueOf(Block.timestamp()).toString() + symbol, "token"}, new Address("NULSd6Hgt3DMt33PKq1hHkFCRbQmbpFtrc4fi"), new String[]{name, symbol, initialAmount.toString(), String.valueOf(decimals)});
        token = new Address(preToken);
        init = true;
    }


    /** VIEW FUNCTIONS */


    @View
    public boolean initialized(){
        return init;
    }

    /**
     * @notice Get aiNULS asset address
     *
     * @return aiNULS Token Contract Address
     */
    @View
    public Address getProjectToken() {
        return token;
    }


    /**
     * @notice Verify if Address is admin
     *
     * @return true if it is admin, false if not
     */
    @View
    public Boolean isAdmin(Address admin) {
        if(projectAdmin.get(admin) == null)
            return false;
        return projectAdmin.get(admin);
    }

    /**
     * @notice Get user balance deposited in lock
     *
     * @return User Balance
     */
    @View
    public BigInteger getUserBalance(Address addr){
        if(userBalance.get(addr) == null)
            return BigInteger.ZERO;
        return userBalance.get(addr);
    }

    /**
     * @notice Get user balance deposited in lock
     *
     * @return User Balance
     */
    @View
    public BigInteger percentageSold(){
        return raised.multiply(BASIS_POINTS).divide(toRaiseNuls);
    }


    @View
    public Boolean isPaused(){
        return paused;
    }

    /** MODIFIER FUNCTIONS */

    protected void onlyAdmin(){
        require(projectAdmin.get(Msg.sender()) != null && projectAdmin.get(Msg.sender()), "Invalid Admin");
    }

    protected void notPaused(){
        require(!paused, "");
    }

    /** MUTABLE NON-OWNER FUNCTIONS */

    /**
     * Deposit funds on Lock
     *
     * */
    @Payable
    public void buyTokens(@Required Address onBehalfOf, BigInteger amount) {

        //Prevent Reentrancy Attacks
        setEntrance();

        //Only allow locks when not paused
        notPaused();

        //Require that nuls sent match the amount to lock
        require(Msg.value().compareTo(amount) >= 0 && amount.compareTo(ONE_NULS) >= 0, "Invalid Amount sent");

        //if exceeds raise amount return if higher than 1 NULS
        if(raised.add(amount).compareTo(toRaiseNuls) > 0 && toRaiseNuls.subtract(raised).compareTo(ONE_NULS) >= 0){
            Msg.sender().transfer(amount.subtract(toRaiseNuls.subtract(raised)));
        }

        //Reject amount over raised and consider only what is left
        amount = (raised.add(amount).compareTo(toRaiseNuls) <= 0) ? amount : toRaiseNuls.subtract(raised);

        BigInteger projectGain = amount.multiply(projectShareFromRaised).divide(BASIS_POINTS);

        BigInteger amountToLock = amount.subtract(projectGain);

        treasury.transfer(projectGain);

        String[][] args = new String[][]{new String[]{Msg.sender().toString()}, new String[]{amountToLock.toString()}};
        depositCtr.callWithReturnValue("lockDeposit", "", args, amountToLock);

        BigInteger payout = amount.multiply(priceInNuls);

        raised = raised.add(amount);

        if(userBalance.get(onBehalfOf) == null){

            userBalance.put(onBehalfOf, amount);

        }else{

            userBalance.put(onBehalfOf, userBalance.get(onBehalfOf).add(amount));

        }

        safeTransfer(token, onBehalfOf, payout);

        setClosure();

    }

    //--------------------------------------------------------------------
    /** MUTABLE OWNER FUNCTIONS */

    public void addAdmin(Address newAdmin){

        onlyAdmin();

        projectAdmin.put(newAdmin, true);

    }

    public void removeAdmin(Address removeAdmin){

        onlyAdmin();
        require(!Msg.sender().equals(removeAdmin), "Can't remove itself");

        projectAdmin.put(removeAdmin, false);

    }

    public void setPaused(){
        onlyAdmin();
        paused = true;
    }

    public void setUnpaused(){
        onlyAdmin();
        paused = false;
    }

    /** Essential to receive funds back from aiNULS
     *
     * @dev DON'T REMOVE IT,
     *      if you do you will be unable to withdraw from aiNULS
     */
    @Payable
    public void _payable() {

    }

    //--------------------------------------------------------------------
    /** INTERNAL FUNCTIONS */

    private BigInteger getBalAINULS(@Required Address owner){
        String[][] args = new String[][]{new String[]{owner.toString()}};
        BigInteger b = new BigInteger(depositCtr.callWithReturnValue("balanceOf", "", args, BigInteger.ZERO));
        return b;
    }

    private void safeTransfer(@Required Address token, @Required Address recipient, @Required BigInteger amount){
        String[][] argsM = new String[][]{new String[]{recipient.toString()}, new String[]{amount.toString()}};
        boolean b = new Boolean(token.callWithReturnValue("transfer", "", argsM, BigInteger.ZERO));
        require(b, "NEII-V1: Failed to transfer");
    }

}