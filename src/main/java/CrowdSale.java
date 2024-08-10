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
    private static final BigInteger ONE_NULS = BigInteger.valueOf(100000000L);
    private static final BigInteger BASIS_POINTS = BigInteger.valueOf(10000);

    public Address depositCtr;

    public Address treasury;

    public Address token;

    public Boolean paused;

    //User Balance
    public Map<Address, BigInteger> userBalance  = new HashMap<>();
    public Map<Address, Boolean>    projectAdmin = new HashMap<>();

    //--------------------------------------------------------------------
    //Initialize Contract
    public CrowdSale(Required String name, @Required String symbol, @Required BigInteger initialAmount, @Required int decimals, Address aiNULSDepositContract_, Address treasury_, Address aiNULS_, Address admin_) {

        treasury = treasury_;
        projectAdmin.put(admin_, true);
        paused = false;

        token = Utils.deploy(new String[]{ "token" + BigInteger.valueOf(Block.timestamp()).toString() + symbol, "token"}, new Address("NULSd6Hgt3DMt33PKq1hHkFCRbQmbpFtrc4fi"), new String[]{name, symbol, initialAmount.toString(), String.valueOf(decimals)});

    }

    /** VIEW FUNCTIONS */

    /**
     * @notice Get aiNULS asset address
     *
     * @return aiNULS Token Contract Address
     */
    @View
    public Address getAINULSCtrAddr() {
        return aiNULS;
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
     * @notice Get user lock time ending
     *
     * @return User lock time ending
     */
    @View
    public BigInteger getUserLockTime(Address addr){
        if(userLockTime.get(addr) == null)
            return BigInteger.ZERO;
        return userLockTime.get(addr);
    }

    @View
    public Boolean isPaused(){
        return paused;
    }

    /** MODIFIER FUNCTIONS */

    public void onlyAdmin(){
        require(projectAdmin.get(Msg.sender()) != null && projectAdmin.get(Msg.sender()), "Invalid Admin");
    }

    public void notPaused(){
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
        require(Msg.value().compareTo(amount)  >= 0, "Invalid Amount sent");

        BigInteger projectGain = amount.multipliedBy(BigInteger.valueOf(7750)).dividedBy(BASIS_POINTS);

        BigInteger amountToLock = amount.subtract(projectGain);

        treasury.transfer(projectGain);

        String[][] args = new String[][]{new String[]{Msg.sender().toString()}, new String[]{amountToLock.toString()}};
        aiNULSDepositContract.callWithReturnValue("lockDeposit", "", args, BigInteger.ZERO);

        if(userBalance.get(onBehalfOf) == null){

            userBalance.put(onBehalfOf, amount);

        }else{

            userBalance.put(onBehalfOf, userBalance.get(onBehalfOf).add(amount));

        }



        setClosure();

    }

    public void claimRewards(Address receiver){

        setEntrance();

        notPaused();

        onlyAdmin();

        BigInteger stakedInAiNULS = getBalAINULS(Msg.address());

        withdrawInAINULS();

        stakeInAINULS(stakedInAiNULS);

        BigInteger balNow = Msg.address().totalBalance();

        receiver.transfer(balNow);

        setClosure();

    }

    public void withdrawAfterLock(){

        setEntrance();

        notPaused();

        require(userLockTime.get(Msg.sender())!= null
                && userLockTime.get(Msg.sender()).compareTo(BigInteger.valueOf(Block.timestamp())) <= 0,
                "Lock still active"
        );

        //Require that user has funds to withdraw
        if(userBalance.get(Msg.sender()) != null && userBalance.get(Msg.sender()).compareTo(BigInteger.ZERO) > 0){

            BigInteger balToWithdraw = userBalance.get(Msg.sender());

            BigInteger stakedInAiNULS = getBalAINULS(Msg.address());

            if((stakedInAiNULS.subtract(balToWithdraw)).compareTo(BigInteger.ZERO) >= 0) {

                withdrawInAINULS();

                stakeInAINULS(stakedInAiNULS.subtract(balToWithdraw));

                userBalance.put(Msg.sender(), BigInteger.ZERO);
            }

        }else{
            require(false, "No Amount Deposited");
        }

        setClosure();
    }

    //--------------------------------------------------------------------
    /** MUTABLE OWNER FUNCTIONS */

    public void setAiNULSDepositContract(Address newDepositCtr){
        onlyAdmin();
        aiNULSDepositContract = newDepositCtr;
    }

    public void setAiNULS(Address newAiNULS){
        onlyAdmin();
        aiNULS = newAiNULS;
    }

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
        BigInteger b = new BigInteger(aiNULSDepositContract.callWithReturnValue("balanceOf", "", args, BigInteger.ZERO));
        return b;
    }

    private void safeTransfer(@Required Address token, @Required Address recipient, @Required BigInteger amount){
        String[][] argsM = new String[][]{new String[]{recipient.toString()}, new String[]{amount.toString()}};
        boolean b = new Boolean(token.callWithReturnValue("transfer", "", argsM, BigInteger.ZERO));
        require(b, "NulswapLendingV1: Failed to transfer");
    }

}