package com.ankit.destination.provisioning;

import android.os.Bundle;

interface IShizukuShellService {
    Bundle runCommand(String command);
}
