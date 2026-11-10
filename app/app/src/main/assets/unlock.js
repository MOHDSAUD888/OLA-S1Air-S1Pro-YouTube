// Frida script run on the dash by frida-inject (see UnlockTab). The app writes
// a copy with __PIN__ replaced by the recovered passcode, then injects it into
// com.olaelectric.profilemanagerservice. It calls the real AIDL entry point on
// the live service instance, feeding the known-correct PIN past the flaky Kanzi
// touch pad. No PIN is faked and no crypto is forged.
Java.perform(function () {
  try {
    Java.choose('com.olaelectric.profilemanagerservice.services.ProfileManagerServiceImpl', {
      onMatch: function (inst) {
        try {
          var res = inst.challengeUnlockByPasscode('__PIN__', false);
          send('UNLOCK isStatus=' + res.isStatus()
               + ' remainingAttempts=' + res.getRemainingAttempts());
        } catch (e) { send('CALL_ERR=' + e); }
      },
      onComplete: function () { send('CHOOSE_DONE'); }
    });
  } catch (e) { send('CHOOSE_ERR=' + e); }
});
